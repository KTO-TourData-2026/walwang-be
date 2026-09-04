package com.server.walwang_be.service;

import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.dto.CourseLegResponse;
import com.server.walwang_be.dto.CourseRecommendRequest;
import com.server.walwang_be.dto.CourseResponse;
import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.SavedCourse;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.PlaceEmbeddingRepository;
import com.server.walwang_be.repo.SavedCourseRepository;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import com.server.walwang_be.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

/**
 * 구간별 거리·시간(legs)이 지점과 어긋나지 않는지.
 *
 * 여기서 잡으려는 건 개수와 인덱스다 — 값 자체는 티맵이 준 걸 그대로 옮길 뿐이라
 * 틀릴 데가 없지만, 몇 개를 어느 자리에 놓느냐는 우리가 정한다.
 * 화면은 stores 목록 사이사이에 이 값을 끼워 넣으므로, 한 칸만 밀려도
 * 엉뚱한 두 가게 사이에 거리가 적힌다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CourseLegsTest {

    private static final double CENTER_LAT = 37.5445;
    private static final double CENTER_LNG = 127.0557;
    private static final double DEGREE_PER_100M = 0.0009;

    @Mock private StoreRepository storeRepository;
    @Mock private StoreSizeStatusRepository storeSizeStatusRepository;
    @Mock private StoreTagCountRepository storeTagCountRepository;
    @Mock private SavedCourseRepository savedCourseRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlaceEmbeddingRepository placeEmbeddingRepository;
    @Mock private TmapClient tmapClient;
    @Mock private NearbyPlaceFinder nearbyPlaceFinder;
    @Mock private TourVerifier tourVerifier;
    @Mock private StoreCandidates storeCandidates;
    @Mock private EmbeddingService embeddingService;

    @InjectMocks private CourseService courseService;

    @BeforeEach
    void stubSurroundings() {
        given(storeCandidates.statuses(any(), any())).willReturn(Map.of());
        given(storeCandidates.hasEvidence(any(), any(), any())).willReturn(true);
        given(storeCandidates.tags(any())).willReturn(Map.of());
        given(storeCandidates.toResponses(any(), any(), any(), anyBoolean())).willReturn(List.of());
        given(nearbyPlaceFinder.around(any())).willReturn(List.of());
        given(placeEmbeddingRepository.existsAny(any(), anyBoolean())).willReturn(false);
    }

    /**
     * 추천은 지도 중심에서 출발하므로 티맵에 넘기는 지점이 가게보다 하나 많다.
     * 그 첫 구간(출발지 -> 첫 가게)은 stores에 가리킬 자리가 없어 legs에서 빠진다 —
     * 남는 건 가게 3곳 사이의 2구간이다.
     */
    @Test
    void 추천은_출발지_구간을_빼고_가게_사이만_준다() {
        givenNearby(store(1), store(3), store(5));
        // 지도 중심 + 가게 3곳 = 지점 4개 -> 티맵 구간 3개.
        givenTmapLegs(leg(100, 90), leg(320, 240), leg(450, 330));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        // 240초 -> 4분, 330초 -> 6분(올림).
        assertThat(response.legs())
                .extracting(CourseLegResponse::fromIndex, CourseLegResponse::toIndex,
                        CourseLegResponse::distance, CourseLegResponse::duration)
                .containsExactly(
                        tuple(0, 1, 320, 4),
                        tuple(1, 2, 450, 6));
    }

    /**
     * 분으로 자르면 0이 되는 짧은 구간도 1분으로 올린다 —
     * 0분은 "바로 옆"으로 읽혀서, 실제로 걸어야 하는 구간과 구분이 안 된다.
     */
    @Test
    void 짧은_구간도_최소_1분으로_올린다() {
        givenNearby(store(1), store(3), store(5));
        givenTmapLegs(leg(100, 90), leg(40, 30), leg(70, 61));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        // 30초 -> 1분, 61초 -> 2분.
        assertThat(response.legs())
                .extracting(CourseLegResponse::duration)
                .containsExactly(1, 2);
    }

    /** 지점이 n개면 구간은 n-1개다. 가게 수가 바뀌어도 이 짝은 깨지지 않아야 한다. */
    @Test
    void legs는_지점_수보다_하나_적다() {
        givenNearby(store(1), store(3), store(5), store(7), store(9));
        givenTmapLegs(leg(100, 90), leg(200, 150), leg(300, 220),
                leg(400, 300), leg(500, 380));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.FULL_DAY), false);

        assertThat(response.legs()).hasSize(response.stores().size() - 1);
    }

    /**
     * 5m 미만이라 티맵을 부르지 않고 건너뛴 구간도 거리 0으로 자리를 지킨다.
     *
     * 같은 건물에 든 두 가게라 걸을 거리가 없는 것이지, 지점이 없는 게 아니다.
     * 빼 버리면 뒤 구간이 한 칸씩 당겨져 화면이 다른 가게 사이에 값을 적는다.
     */
    @Test
    void 건너뛴_구간도_0으로_자리를_지킨다() {
        givenNearby(store(1), store(3), store(5));
        // 가운데가 같은 건물이라 걸을 거리가 없던 경우.
        givenTmapLegs(leg(100, 90), leg(0, 0), leg(450, 330));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        // 걷지 않은 구간이라 올림도 하지 않는다 — 0분 그대로다.
        assertThat(response.legs())
                .extracting(CourseLegResponse::fromIndex, CourseLegResponse::toIndex,
                        CourseLegResponse::distance, CourseLegResponse::duration)
                .containsExactly(
                        tuple(0, 1, 0, 0),
                        tuple(1, 2, 450, 6));
    }

    /** 티맵이 실패하면 경로선과 함께 legs도 비운다. 직선 추정치를 구간값으로 적어 줄 수는 없다. */
    @Test
    void 티맵이_실패하면_legs는_빈_배열이다() {
        givenNearby(store(1), store(3), store(5));
        given(tmapClient.walkRoute(any())).willReturn(Optional.empty());

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        assertThat(response.legs()).isEmpty();
        // 거리·시간 자체는 직선 추정으로 여전히 채워진다.
        assertThat(response.totalDistance()).isPositive();
    }

    /**
     * 저장된 코스에는 출발지가 없어 지점이 곧 가게다.
     * 추천에서 뺐던 첫 구간만큼 어긋나지 않고, 같은 자리에 같은 값이 나와야 한다.
     */
    @Test
    void 저장된_코스도_가게_사이_구간을_그대로_준다() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        givenSavedCourse(userId, courseId, store(1), store(3), store(5));
        // 가게 3곳이 그대로 지점 3개 -> 티맵 구간 2개.
        givenTmapLegs(leg(320, 240), leg(450, 330));

        CourseResponse response = courseService.findOne(userId, courseId, false);

        assertThat(response.legs())
                .extracting(CourseLegResponse::fromIndex, CourseLegResponse::toIndex,
                        CourseLegResponse::distance, CourseLegResponse::duration)
                .containsExactly(
                        tuple(0, 1, 320, 4),
                        tuple(1, 2, 450, 6));
    }

    private void givenNearby(Store... stores) {
        List<Store> nearby = List.of(stores);
        given(storeRepository.findWithinRadius(anyDouble(), anyDouble(), anyInt()))
                .willReturn(nearby);
        given(tourVerifier.verifiedAmong(any())).willReturn(
                nearby.stream().map(Store::getStoreId).collect(Collectors.toSet()));
        // legs와 짝을 맞춰 볼 것은 개수뿐이라, 코스에 담긴 가게 수만 그대로 되돌려 준다.
        given(storeCandidates.toResponses(any(), any(), any(), anyBoolean()))
                .willAnswer(call -> Collections.<CourseStoreResponse>nCopies(
                        ((List<?>) call.getArgument(0)).size(), null));
    }

    private void givenSavedCourse(UUID userId, UUID courseId, Store... stores) {
        User user = org.mockito.Mockito.mock(User.class);
        given(user.getUserId()).willReturn(userId);

        SavedCourse course = SavedCourse.builder()
                .user(user)
                .title("저장한 코스")
                .dogSize(DogSize.SMALL_MEDIUM)
                .purposes(List.of(CoursePurpose.CAFE))
                .duration(CourseDuration.HALF_DAY)
                .description(null)
                .build();
        for (int i = 0; i < stores.length; i++) {
            course.addStore(stores[i], i + 1);
        }
        set(course, "courseId", courseId);

        given(savedCourseRepository.findWithStoresByCourseId(courseId))
                .willReturn(Optional.of(course));
    }

    /** 티맵이 이 구간들을 돌려줬다고 둔다. 경로선 좌표는 여기서 볼 게 아니라 대충 채운다. */
    private void givenTmapLegs(TmapClient.RouteLeg... legs) {
        List<TmapClient.RouteLeg> list = List.of(legs);
        int distance = list.stream().mapToInt(TmapClient.RouteLeg::distanceMeters).sum();
        int time = list.stream().mapToInt(TmapClient.RouteLeg::timeSeconds).sum();

        given(tmapClient.walkRoute(any())).willReturn(Optional.of(new TmapClient.WalkRoute(
                List.of(List.of(CENTER_LAT, CENTER_LNG)), list, distance, time, list.size())));
    }

    private TmapClient.RouteLeg leg(int distance, int time) {
        return new TmapClient.RouteLeg(distance, time);
    }

    private CourseRecommendRequest request(CourseDuration duration) {
        return new CourseRecommendRequest(
                DogSize.SMALL_MEDIUM, List.of(CoursePurpose.CAFE), duration,
                CENTER_LAT, CENTER_LNG, null);
    }

    /** 지도 중심에서 북쪽으로 떨어진 가게. 단위는 100m 남짓이다. */
    private Store store(int hundredMeters) {
        Store store = Store.builder()
                .externalId(UUID.randomUUID().toString())
                .name("가게")
                .lat(CENTER_LAT + hundredMeters * DEGREE_PER_100M)
                .lng(CENTER_LNG)
                .address("서울 성동구")
                .type("cafe")
                .build();
        set(store, "storeId", UUID.randomUUID());
        return store;
    }

    /** DB가 채우는 식별자라 빌더로는 넣을 수 없다. */
    private void set(Object target, String field, Object value) {
        try {
            var declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
