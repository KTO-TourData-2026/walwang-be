package com.server.walwang_be.service;

import com.server.walwang_be.client.TourApiClient;
import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 지도·코스·대안·상세가 같은 가게에 같은 공사 매칭 판정을 내는지.
 *
 * 네 곳 모두 TourVerifier.within(중심, 반경)을 쓴다(코스는 CourseServiceRankingTest 가 확인한다).
 * 여기서는 그 진입점의 성질을 고정한다 — 반경 안 가게의 판정이 중심·반경과 상관없이
 * "성동구 전체를 한 번에 매칭한 결과"와 똑같아야 한다.
 *
 * 데이터는 시드를 고정한 무작위 배치에, 어긋나기 쉬운 경우를 섞었다.
 *   - 이름이 비슷한 가게가 흔하다(같은 브랜드 여러 지점, 지점명 붙은 공사 제목)
 *   - 같은 제목의 공사 항목이 서로 다른 곳에 있다
 *   - 한 가게에 공사 항목 둘이 붙는다(중복이라 둘 다 버려야 한다)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TourMatchConsistencyTest {

    private static final double CENTER_LAT = 37.5550;
    private static final double CENTER_LNG = 127.0430;
    private static final double METERS_PER_LAT = 111_195.0;
    private static final double METERS_PER_LNG = METERS_PER_LAT * Math.cos(Math.toRadians(CENTER_LAT));

    /** 상세(0), 대안(1km), 코스(1.5/3/5km), 지도(기본 2km, 최대 5km). */
    private static final int[] RADII = {0, 1_000, 1_500, 2_000, 3_000, 5_000};

    private static final String[] BRANDS = {
            "룩캐스트", "어니언", "블루보틀", "올리브영", "스탠드오일", "노티드", "대림창고", "포인트오브뷰",
            "카페 성수", "성수연방", "로우키", "누데이크"};
    private static final String[] BRANCHES = {"", " 성수점", " 서울숲점", " 뚝섬점", " 왕십리점"};

    @Mock
    StoreRepository storeRepository;
    @Mock
    TourApiClient tourApiClient;

    private final List<Store> stores = new ArrayList<>();
    private final List<Map<String, Object>> tourItems = new ArrayList<>();
    private TourVerifier verifier;
    private Set<UUID> reference;

    @BeforeEach
    void setUp() {
        Random random = new Random(20260916L);
        // 성동구 크기쯤(약 6km × 5km)에 가게를 흩뿌리고, 일부 가게 근처에 공사 항목을 둔다.
        for (int i = 0; i < 1_500; i++) {
            String name = BRANDS[random.nextInt(BRANDS.length)] + BRANCHES[random.nextInt(BRANCHES.length)];
            stores.add(store(name, (random.nextDouble() - 0.5) * 5_000, (random.nextDouble() - 0.5) * 6_000));
        }
        for (int i = 0; i < 120; i++) {
            Store near = stores.get(random.nextInt(stores.size()));
            String title = BRANDS[random.nextInt(BRANDS.length)] + BRANCHES[random.nextInt(BRANCHES.length)];
            tourItems.add(item(title, north(near) + (random.nextDouble() - 0.5) * 500,
                    east(near) + (random.nextDouble() - 0.5) * 500));
        }
        // 같은 제목의 항목이 멀리 떨어진 두 곳에 있다. 제목을 키로 쌓으면 서로 덮어쓴다.
        Store twinA = store("스타벅스 성수점", 1_800, -2_200);
        Store twinB = store("스타벅스 성수점", -1_900, 2_300);
        stores.add(twinA);
        stores.add(twinB);
        tourItems.add(item("스타벅스 성수점", north(twinA) + 20, east(twinA)));
        tourItems.add(item("스타벅스 성수점", north(twinB) - 20, east(twinB)));
        // 경계 함정: 반경(1km) 바로 안에 비슷한 이름, 진짜 주인은 반경 밖.
        // 반경 안 가게만 후보로 두면 안쪽 가게가 접두어 규칙으로 대신 붙는다.
        Store trapInside = store("헤이러브", 950, 0);
        Store trapOwner = store("헤이러브 성수점", 1_250, 0);
        stores.add(trapInside);
        stores.add(trapOwner);
        tourItems.add(item("헤이러브 성수점", 1_200, 0));
        // 한 가게에 항목 둘. 둘 다 버려져야 한다.
        Store doubled = store("플라츠 성수", -700, -900);
        stores.add(doubled);
        tourItems.add(item("플라츠 성수", north(doubled) + 10, east(doubled)));
        tourItems.add(item("플라츠", north(doubled) - 15, east(doubled)));

        given(storeRepository.findWithinRadius(anyDouble(), anyDouble(), anyInt())).willAnswer(invocation -> {
            double lat = invocation.getArgument(0);
            double lng = invocation.getArgument(1);
            int radius = invocation.getArgument(2);
            return stores.stream()
                    .filter(store -> GeoDistance.between(lat, lng, store.getLat(), store.getLng()) <= radius)
                    .toList();
        });
        given(tourApiClient.areaBasedList(anyString(), anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                .willReturn(new TourApiClient.TourResponse(tourItems.size(), tourItems));

        TourStoreMatcher matcher = new TourStoreMatcher(storeRepository);
        TourDataProvider provider = new TourDataProvider(tourApiClient);
        verifier = new TourVerifier(provider, matcher, storeRepository);

        // 기준: 성동구 전체 항목 × 전체 가게를 한 번에.
        reference = matcher.matchAll(provider.seongdongPoints(), stores).matched().values().stream()
                .map(TourStoreMatcher.Match::storeId)
                .collect(Collectors.toSet());

        assertThat(reference).as("같은 제목의 두 항목이 모두 살아 있어야 한다")
                .contains(twinA.getStoreId(), twinB.getStoreId());
        assertThat(reference).as("경계 함정은 진짜 주인에게 붙어야 한다")
                .contains(trapOwner.getStoreId())
                .doesNotContain(trapInside.getStoreId());
        assertThat(reference).as("항목 둘이 붙은 가게는 버려져야 한다")
                .doesNotContain(doubled.getStoreId());
        assertThat(reference.size()).as("무작위 배치에서도 매칭이 충분히 생겨야 검사가 의미 있다")
                .isGreaterThan(30);
    }

    @Test
    void 반경_안_가게의_판정은_중심과_반경에_상관없이_전체_매칭과_같다() {
        List<double[]> centers = List.of(
                new double[]{0, 0}, new double[]{900, -1_300}, new double[]{-1_700, 2_100},
                new double[]{2_300, 2_600}, new double[]{-2_400, -2_900});

        int checked = 0;
        for (double[] center : centers) {
            double lat = CENTER_LAT + center[0] / METERS_PER_LAT;
            double lng = CENTER_LNG + center[1] / METERS_PER_LNG;
            for (int radius : RADII) {
                TourVerifier.Scope scope = verifier.within(lat, lng, radius);
                for (Store store : scope.stores()) {
                    assertThat(scope.isVerified(store))
                            .as("중심 %s, 반경 %dm, 가게 %s", List.of(center[0], center[1]), radius, store.getName())
                            .isEqualTo(reference.contains(store.getStoreId()));
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(5_000);
    }

    /**
     * 가게 자신을 중심으로 반경 0 — 상세 화면의 판정. 모든 가게가 전체 매칭과 같아야 한다.
     * 매칭된 가게가 상세에서만 배지를 잃거나 얻으면 목록과 상세가 서로 다른 말을 한다.
     */
    @Test
    void 상세_판정은_모든_가게에서_전체_매칭과_같다() {
        for (Store store : stores) {
            assertThat(verifier.within(store.getLat(), store.getLng(), 0).isVerified(store))
                    .as(store.getName())
                    .isEqualTo(reference.contains(store.getStoreId()));
        }
    }

    /**
     * 이 검사가 실제로 무언가를 잡는지. 예전처럼 반경 안 가게만 후보로 두면
     * 같은 데이터에서 판정이 어긋나는 가게가 생긴다.
     */
    @Test
    void 반경_안_가게만_후보로_두면_어긋나는_가게가_생긴다() {
        int mismatches = 0;
        for (int radius : new int[]{1_000, 1_500, 2_000}) {
            List<Store> insideOnly = stores.stream()
                    .filter(store -> GeoDistance.between(CENTER_LAT, CENTER_LNG, store.getLat(), store.getLng()) <= radius)
                    .toList();
            Set<UUID> old = verifier.verifiedNear(insideOnly, CENTER_LAT, CENTER_LNG, 100_000);
            for (Store store : insideOnly) {
                if (old.contains(store.getStoreId()) != reference.contains(store.getStoreId())) {
                    mismatches++;
                }
            }
        }
        assertThat(mismatches).isPositive();
    }

    /** 지도와 대안을 실제로 돌려, 같은 가게에 대해 서로 다른 답을 내지 않는지. */
    @Test
    void 지도와_대안이_같은_가게를_같게_판정한다() {
        StoreService storeService = mock(StoreService.class);
        MapStoreService map = new MapStoreService(verifier, storeService, mock(StoreSizeStatusRepository.class));

        StoreSizeStatusRepository statusRepository = mock(StoreSizeStatusRepository.class);
        StoreTagCountRepository tagRepository = mock(StoreTagCountRepository.class);
        given(statusRepository.findByStore_StoreIdInAndDemo(any(), anyBoolean())).willReturn(List.of());
        given(tagRepository.findByStore_StoreIdInAndDemo(any(), anyBoolean())).willReturn(List.of());
        AlternativeStoreService alternatives = new AlternativeStoreService(
                storeRepository, verifier, new StoreCandidates(statusRepository, tagRepository));

        // 지도: 반경 5km(최대) 안에서 매칭된 가게
        map.findVerifiedNearby(CENTER_LAT, CENTER_LNG, 5_000, null, false);
        ArgumentCaptor<List<Store>> pins = ArgumentCaptor.captor();
        verify(storeService).toCards(pins.capture(), isNull(), eq(false));
        Map<UUID, Boolean> onMap = new HashMap<>();
        pins.getValue().forEach(store -> onMap.put(store.getStoreId(), true));
        assertThat(onMap.keySet()).isEqualTo(stores.stream()
                .filter(store -> GeoDistance.between(CENTER_LAT, CENTER_LNG, store.getLat(), store.getLng()) <= 5_000)
                .map(Store::getStoreId)
                .filter(reference::contains)
                .collect(Collectors.toSet()));

        // 대안: 리뷰 근거가 없으니(상태 행 없음) 나오는 곳은 전부 매칭으로 들어온 곳이다.
        // 지도가 매칭으로 보여준 가게여야 하고, 매칭 안 된 가게는 나오지 않아야 한다.
        Set<UUID> fromAlternatives = new LinkedHashSet<>();
        for (Store base : stores.subList(0, 200)) {
            given(storeRepository.findById(base.getStoreId())).willReturn(Optional.of(base));
            alternatives.findAlternatives(base.getStoreId(), DogSize.SMALL_MEDIUM, false).stream()
                    .map(CourseStoreResponse::storeId)
                    .forEach(fromAlternatives::add);
        }
        assertThat(fromAlternatives).isNotEmpty();
        assertThat(reference).containsAll(fromAlternatives);
        fromAlternatives.stream()
                .map(id -> stores.stream().filter(store -> store.getStoreId().equals(id)).findFirst().orElseThrow())
                .filter(store -> GeoDistance.between(CENTER_LAT, CENTER_LNG, store.getLat(), store.getLng()) <= 5_000)
                .forEach(store -> assertThat(onMap).as(store.getName()).containsKey(store.getStoreId()));
    }

    // ── 픽스처 ─────────────────────────────────────────────────

    private static Store store(String name, double northMeters, double eastMeters) {
        Store store = Store.builder().name(name).type("카페").address("서울 성동구")
                .lat(CENTER_LAT + northMeters / METERS_PER_LAT)
                .lng(CENTER_LNG + eastMeters / METERS_PER_LNG)
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

    private static double north(Store store) {
        return (store.getLat() - CENTER_LAT) * METERS_PER_LAT;
    }

    private static double east(Store store) {
        return (store.getLng() - CENTER_LNG) * METERS_PER_LNG;
    }

    private static Map<String, Object> item(String title, double northMeters, double eastMeters) {
        return Map.of(
                "title", title,
                "mapy", String.valueOf(CENTER_LAT + northMeters / METERS_PER_LAT),
                "mapx", String.valueOf(CENTER_LNG + eastMeters / METERS_PER_LNG));
    }
}
