package com.server.walwang_be.service;

import com.server.walwang_be.client.TourApiClient;
import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 대안 추천의 후보 판정. 코스 추천과 같은 규칙(StoreCandidates.hasEvidence)에 공사 매칭이 실제로 들어가는지 본다.
 *
 *   포함: 공사 매칭됨(UNKNOWN 이어도) 또는 그 크기로 POSSIBLE
 *   제외: 그 크기로 IMPOSSIBLE(매칭돼도), 매칭도 리뷰도 없는 UNKNOWN
 *
 * 공사 API 클라이언트만 목이다. 공사 데이터 제공·매칭·판정은 실제 코드가 돈다.
 *
 * 배치(기준 가게 북쪽/남쪽/동쪽으로 떨어뜨림):
 *   A 카페 오니온 성수   북 300m   UNKNOWN     공사 지점 있음  → 포함(매칭)
 *   B 대림창고           동 500m   UNKNOWN     공사 지점 없음  → 제외
 *   C 룩캐스트           북 950m   UNKNOWN     근처 공사 지점은 1.25km 의 "룩캐스트 성수점" 것 → 제외
 *   D 블루보틀 성수      남 400m   POSSIBLE    공사 지점 없음  → 포함(리뷰)
 *   E 어니언 아지트      남 200m   IMPOSSIBLE  공사 지점 있음  → 제외
 *   F 무엇 편의점        동 100m   POSSIBLE    업종이 다름     → 제외
 *   G 멀리 카페          동 1.1km  POSSIBLE    반경 밖         → 제외
 *   O 룩캐스트 성수점    북 1.25km (반경 밖, C 옆 공사 지점의 진짜 주인)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlternativeStoreServiceTest {

    private static final double BASE_LAT = 37.5445;
    private static final double BASE_LNG = 127.0560;
    private static final double METERS_PER_LAT = 111_195.0;
    private static final double METERS_PER_LNG = METERS_PER_LAT * Math.cos(Math.toRadians(BASE_LAT));

    @Mock
    StoreRepository storeRepository;
    @Mock
    StoreSizeStatusRepository storeSizeStatusRepository;
    @Mock
    StoreTagCountRepository storeTagCountRepository;
    @Mock
    TourApiClient tourApiClient;

    private TourVerifier tourVerifier;
    private AlternativeStoreService service;

    private Store base;
    private Store onion;
    private Store daelim;
    private Store lookcastInside;
    private Store bluebottle;
    private Store anion;
    private Store convenience;
    private Store far;
    private Store lookcastOwner;
    private List<Store> all;

    @BeforeEach
    void setUp() {
        tourVerifier = new TourVerifier(
                new TourDataProvider(tourApiClient), new TourStoreMatcher(storeRepository), storeRepository);
        service = new AlternativeStoreService(storeRepository, tourVerifier,
                new StoreCandidates(storeSizeStatusRepository, storeTagCountRepository));

        base = store("기준 카페", "카페", 0, 0);
        onion = store("카페 오니온 성수", "카페", 300, 0);
        daelim = store("대림창고", "카페", 0, 500);
        lookcastInside = store("룩캐스트", "카페", 950, 0);
        bluebottle = store("블루보틀 성수", "카페", -400, 0);
        anion = store("어니언 아지트", "카페", -200, 0);
        convenience = store("무엇 편의점", "편의점", 0, 100);
        far = store("멀리 카페", "카페", 0, 1_100);
        lookcastOwner = store("룩캐스트 성수점", "카페", 1_250, 0);
        all = List.of(base, onion, daelim, lookcastInside, bluebottle, anion, convenience, far, lookcastOwner);

        given(storeRepository.findById(base.getStoreId())).willReturn(Optional.of(base));
        // DB 처럼 반경 안의 것만 돌려준다.
        given(storeRepository.findWithinRadius(anyDouble(), anyDouble(), anyInt())).willAnswer(invocation -> {
            double lat = invocation.getArgument(0);
            double lng = invocation.getArgument(1);
            int radius = invocation.getArgument(2);
            return all.stream()
                    .filter(store -> GeoDistance.between(lat, lng, store.getLat(), store.getLng()) <= radius)
                    .toList();
        });

        List<StoreSizeStatus> statuses = new ArrayList<>();
        // 건수가 등급의 표본 하한이다(AvailabilityStatus) — POSSIBLE은 4건, IMPOSSIBLE은 3건부터다.
        statuses.add(status(bluebottle, 4.0, 0.0));
        statuses.add(status(anion, 0.0, 3.0));
        statuses.add(status(convenience, 4.0, 0.0));
        statuses.add(status(far, 4.0, 0.0));
        given(storeSizeStatusRepository.findByStore_StoreIdInAndDemo(any(), eq(false))).willReturn(statuses);
        given(storeTagCountRepository.findByStore_StoreIdInAndDemo(any(), eq(false))).willReturn(List.of());

        given(tourApiClient.areaBasedList(anyString(), anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                .willReturn(new TourApiClient.TourResponse(3, List.of(
                        tourItem("카페 오니온 성수", 310, 0),
                        tourItem("어니언 아지트", -205, 0),
                        tourItem("룩캐스트 성수점", 1_200, 0))));
    }

    @Test
    void 공사_매칭된_UNKNOWN과_POSSIBLE만_가까운_순으로_나온다() {
        List<CourseStoreResponse> result = service.findAlternatives(base.getStoreId(), DogSize.SMALL_MEDIUM, false);

        assertThat(result).extracting(CourseStoreResponse::name)
                .containsExactly("카페 오니온 성수", "블루보틀 성수");
        // 매칭으로 들어온 곳은 등급이 UNKNOWN 그대로 나간다 — 배지가 아니라 후보 자격에만 쓰인다.
        assertThat(result).extracting(CourseStoreResponse::status)
                .containsExactly(AvailabilityStatus.UNKNOWN, AvailabilityStatus.POSSIBLE);
        assertThat(result).extracting(CourseStoreResponse::arrivalOrder).containsExactly(1, 2);
    }

    @Test
    void 매칭돼도_IMPOSSIBLE이면_빠지고_근거_없는_UNKNOWN도_빠진다() {
        List<UUID> ids = service.findAlternatives(base.getStoreId(), DogSize.SMALL_MEDIUM, false).stream()
                .map(CourseStoreResponse::storeId).toList();

        assertThat(tourVerifier.verifiedNear(all, BASE_LAT, BASE_LNG, 1_300)).contains(anion.getStoreId());
        assertThat(ids).doesNotContain(anion.getStoreId(), daelim.getStoreId(),
                convenience.getStoreId(), far.getStoreId(), base.getStoreId());
    }

    @Test
    void 공사_API가_실패하면_리뷰_근거만으로_고르고_정상_응답한다() {
        given(tourApiClient.areaBasedList(anyString(), anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                .willThrow(new IllegalStateException("공사 API 503"));

        List<CourseStoreResponse> result = service.findAlternatives(base.getStoreId(), DogSize.SMALL_MEDIUM, false);

        // 매칭으로만 들어오던 오니온이 빠지고, POSSIBLE 인 블루보틀만 남는다.
        assertThat(result).extracting(CourseStoreResponse::name).containsExactly("블루보틀 성수");
    }

    /**
     * 매칭 후보를 1km 로만 읽던 때의 문제. 경계 밖 진짜 주인이 빠지면 접두어 규칙이
     * 안쪽의 "룩캐스트" 를 "룩캐스트 성수점" 공사 항목에 붙인다.
     */
    @Test
    void 경계_밖_진짜_주인까지_읽어_안쪽_비슷한_이름에_잘못_붙지_않는다() {
        List<Store> insideOnly = all.stream()
                .filter(store -> GeoDistance.between(BASE_LAT, BASE_LNG, store.getLat(), store.getLng()) <= 1_000)
                .toList();
        // 예전 방식이면 잘못 붙는다.
        assertThat(tourVerifier.verifiedNear(insideOnly, BASE_LAT, BASE_LNG, 1_300)).contains(lookcastInside.getStoreId());

        List<CourseStoreResponse> result = service.findAlternatives(base.getStoreId(), DogSize.SMALL_MEDIUM, false);

        assertThat(result).extracting(CourseStoreResponse::storeId).doesNotContain(lookcastInside.getStoreId());
        verify(storeRepository).findWithinRadius(BASE_LAT, BASE_LNG,
                TourVerifier.matchPoolRadius(1_000));
        assertThat(TourVerifier.matchPoolRadius(1_000)).isEqualTo(1_600);
        assertThat(TourVerifier.matchPointRadius(1_000)).isEqualTo(1_300);
    }

    @Test
    void 공사_지점이_반경_밖에만_있으면_매칭_없이_리뷰로만_고른다() {
        given(tourApiClient.areaBasedList(anyString(), anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                .willReturn(new TourApiClient.TourResponse(1, List.of(tourItem("카페 오니온 성수", 5_000, 0))));

        assertThat(service.findAlternatives(base.getStoreId(), DogSize.SMALL_MEDIUM, false))
                .extracting(CourseStoreResponse::name).containsExactly("블루보틀 성수");
    }

    // ── 픽스처 ─────────────────────────────────────────────────

    private static Store store(String name, String type, double northMeters, double eastMeters) {
        Store store = Store.builder().name(name).type(type).address("서울 성동구")
                .lat(BASE_LAT + northMeters / METERS_PER_LAT)
                .lng(BASE_LNG + eastMeters / METERS_PER_LNG)
                .build();
        try {
            var field = Store.class.getDeclaredField("storeId");
            field.setAccessible(true);
            field.set(store, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return store;
    }

    private static StoreSizeStatus status(Store store, double possible, double impossible) {
        StoreSizeStatus status = StoreSizeStatus.builder().store(store).size(DogSize.SMALL_MEDIUM).demo(false).build();
        status.applyRecomputed(possible, impossible, (int) possible, (int) impossible, Instant.now());
        return status;
    }

    /** 공사 응답 항목. 좌표는 JSON 처럼 문자열로 온다. */
    private static Map<String, Object> tourItem(String title, double northMeters, double eastMeters) {
        return Map.of(
                "title", title,
                "mapy", String.valueOf(BASE_LAT + northMeters / METERS_PER_LAT),
                "mapx", String.valueOf(BASE_LNG + eastMeters / METERS_PER_LNG));
    }
}
