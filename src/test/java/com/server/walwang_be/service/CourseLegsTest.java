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
        given(storeCandidates.statuses(any(), any(), anyBoolean())).willReturn(Map.of());
        given(storeCandidates.hasEvidence(any(), any(), any())).willReturn(true);
        given(storeCandidates.tags(any(), anyBoolean())).willReturn(Map.of());
        // legs와 짝을 맞춰 볼 것은 개수뿐이라, 코스에 담긴 가게 수만 그대로 되돌려 준다.
        // 출발점은 서비스가 붙이므로 여기서는 가게 몫만 채운다.
        given(storeCandidates.toResponses(any(), any(), any(), anyBoolean()))
                .willAnswer(call -> Collections.<CourseStoreResponse>nCopies(
                        ((List<?>) call.getArgument(0)).size(), null));
        given(nearbyPlaceFinder.around(any())).willReturn(List.of());
        given(placeEmbeddingRepository.existsAny(any(), anyBoolean())).willReturn(false);
    }

    /**
     * 출발점이 stores[0]에 들어가므로 티맵 구간이 빠짐없이 legs가 된다 —
     * 첫 구간(출발점 -> 첫 가게)도 인덱스 (0, 1)로 실린다.
     */
    @Test
    void 추천은_출발점_구간부터_모든_구간을_준다() {
        givenNearby(store(1), store(3), store(5));
        // 출발점 + 가게 3곳 = 지점 4개 -> 티맵 구간 3개.
        givenTmapLegs(leg(100, 90), leg(320, 240), leg(450, 330));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        // 90초 -> 2분, 240초 -> 4분, 330초 -> 6분(올림).
        assertThat(response.legs())
                .extracting(CourseLegResponse::fromIndex, CourseLegResponse::toIndex,
                        CourseLegResponse::distance, CourseLegResponse::duration)
                .containsExactly(
                        tuple(0, 1, 100, 2),
                        tuple(1, 2, 320, 4),
                        tuple(2, 3, 450, 6));
    }

    /** 출발점은 가게가 아니라는 표시(nil UUID)를 달고 요청 좌표에 놓인다. */
    @Test
    void 추천_stores_맨_앞은_출발점이다() {
        givenNearby(store(1), store(3), store(5));
        givenTmapLegs(leg(100, 90), leg(320, 240), leg(450, 330));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        assertThat(response.stores()).hasSize(4);
        CourseStoreResponse start = response.stores().get(0);
        assertThat(start.storeId()).isEqualTo(CourseStoreResponse.START_POINT_ID);
        assertThat(start.storeId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(start.name()).isEqualTo("출발점");
        assertThat(start.lat()).isEqualTo(CENTER_LAT);
        assertThat(start.lng()).isEqualTo(CENTER_LNG);
        assertThat(start.arrivalOrder()).isZero();
        assertThat(start.type()).isNull();
        assertThat(start.address()).isNull();
        assertThat(start.status()).isNull();
        assertThat(start.reviewCount()).isZero();
        assertThat(start.tags()).isEmpty();
    }

    /** 첫 구간을 legs에 넣었으니 구간 합이 곧 총거리다. */
    @Test
    void legs_합계가_총거리와_같다() {
        givenNearby(store(1), store(3), store(5));
        givenTmapLegs(leg(100, 90), leg(320, 240), leg(450, 330));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        assertThat(response.legs().stream().mapToInt(CourseLegResponse::distance).sum())
                .isEqualTo(response.totalDistance())
                .isEqualTo(870);
    }

    /** 갈 곳이 없으면 출발점도 붙이지 않는다. 화면은 빈 배열로 "결과 없음"을 가른다. */
    @Test
    void 후보가_없으면_stores는_비어_있다() {
        givenNearby();

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        assertThat(response.stores()).isEmpty();
        assertThat(response.legs()).isEmpty();
    }

    /**
     * 출발점으로 쓴 가게도 조건에 맞으면 후보에서 빠지지 않는다.
     * 거리 0이라 맨 먼저 담기고, 출발점 바로 뒤에 다시 나와 사이 구간은 0m/0분이다.
     */
    @Test
    void 출발점_가게도_코스에_담긴다() {
        Store atStart = store(0);
        givenNearby(store(3), atStart, store(5));
        // 출발점과 첫 가게가 같은 자리라 티맵이 건너뛴 구간.
        givenTmapLegs(leg(0, 0), leg(270, 200), leg(180, 130));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.HALF_DAY), false);

        List<TmapClient.Waypoint> waypoints = capturedWaypoints().get(0);
        assertThat(waypoints.get(1).lat()).isEqualTo(atStart.getLat());
        assertThat(waypoints.get(0).lat()).isEqualTo(waypoints.get(1).lat());
        assertThat(waypoints.get(0).lng()).isEqualTo(waypoints.get(1).lng());
        assertThat(response.stores()).hasSize(4);
        assertThat(response.legs().get(0))
                .extracting(CourseLegResponse::fromIndex, CourseLegResponse::toIndex,
                        CourseLegResponse::distance, CourseLegResponse::duration)
                .containsExactly(0, 1, 0, 0);
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

        // 90초 -> 2분, 30초 -> 1분, 61초 -> 2분.
        assertThat(response.legs())
                .extracting(CourseLegResponse::duration)
                .containsExactly(2, 1, 2);
    }

    /** 지점이 n개면 구간은 n-1개다. 가게 수가 바뀌어도 이 짝은 깨지지 않아야 한다. */
    @Test
    void legs는_지점_수보다_하나_적다() {
        givenNearby(store(1), store(3), store(5), store(7), store(9));
        // 출발점 + 가게 5곳 = 지점 6개 -> 티맵 구간 5개.
        givenTmapLegs(leg(100, 90), leg(200, 150), leg(300, 220),
                leg(400, 300), leg(500, 380));

        CourseResponse response = courseService.recommend(
                request(CourseDuration.FULL_DAY), false);

        assertThat(response.stores()).hasSize(6);
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
                        tuple(0, 1, 100, 2),
                        tuple(1, 2, 0, 0),
                        tuple(2, 3, 450, 6));
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
     * 추천 결과를 저장해 조회하면 추천과 같은 지점을 같은 순서로 티맵에 넘기고,
     * 같은 모양(출발점 + 가게, 같은 legs, 같은 총거리)으로 돌려준다.
     */
    @Test
    void 저장된_코스는_추천과_같은_코스로_나온다() {
        Store first = store(1);
        Store second = store(3);
        Store third = store(5);
        givenNearby(first, second, third);
        givenTmapLegs(leg(100, 90), leg(320, 240), leg(450, 330));
        CourseResponse recommended = courseService.recommend(request(CourseDuration.HALF_DAY), false);

        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        givenSavedCourse(userId, courseId, CENTER_LAT, CENTER_LNG, first, second, third);
        CourseResponse saved = courseService.findOne(userId, courseId, false);

        List<List<TmapClient.Waypoint>> calls = capturedWaypoints();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).isEqualTo(calls.get(0));
        assertThatSameCourse(saved, recommended);
    }

    /** 가게가 1곳이어도 출발점 -> 가게 구간이 있어 추천과 조회가 같다. */
    @Test
    void 가게가_1곳인_코스도_추천과_조회가_같다() {
        Store only = store(2);
        givenNearby(only);
        givenTmapLegs(leg(200, 150));
        CourseResponse recommended = courseService.recommend(request(CourseDuration.HALF_DAY), false);

        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        givenSavedCourse(userId, courseId, CENTER_LAT, CENTER_LNG, only);
        CourseResponse saved = courseService.findOne(userId, courseId, false);

        List<List<TmapClient.Waypoint>> calls = capturedWaypoints();
        assertThat(calls.get(1)).isEqualTo(calls.get(0)).hasSize(2);
        assertThat(saved.stores()).hasSize(2);
        assertThat(saved.legs()).hasSize(1);
        assertThat(saved.totalDistance()).isEqualTo(200);
        assertThatSameCourse(saved, recommended);
    }

    /**
     * 출발 좌표를 남기기 전에 저장한 코스는 첫 가게에서 출발한다.
     * 모양은 새 코스와 같고(출발점 + 가게), 첫 구간은 같은 자리라 0이다.
     */
    @Test
    void 출발_좌표가_없는_옛_코스는_첫_가게에서_출발한다() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        Store first = store(1);
        givenSavedCourse(userId, courseId, null, null, first, store(3), store(5));
        givenTmapLegs(leg(0, 0), leg(200, 150), leg(200, 150));

        CourseResponse response = courseService.findOne(userId, courseId, false);

        TmapClient.Waypoint start = capturedWaypoints().get(0).get(0);
        assertThat(start.lat()).isEqualTo(first.getLat());
        assertThat(start.lng()).isEqualTo(first.getLng());
        assertThat(response.stores()).hasSize(4);
        assertThat(response.stores().get(0).storeId()).isEqualTo(CourseStoreResponse.START_POINT_ID);
        assertThat(response.stores().get(0).lat()).isEqualTo(first.getLat());
        assertThat(response.legs()).hasSize(3);
        assertThat(response.legs().get(0).distance()).isZero();
        assertThat(response.totalDistance()).isEqualTo(400);
    }

    /** 티맵이 실패해도 저장된 출발점에서부터 직선으로 센다. */
    @Test
    void 티맵이_실패해도_저장_코스는_출발점부터_센다() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        givenSavedCourse(userId, courseId, CENTER_LAT, CENTER_LNG, store(1), store(3));
        given(tmapClient.walkRoute(any())).willReturn(Optional.empty());

        CourseResponse response = courseService.findOne(userId, courseId, false);

        // 출발점 -> 100m 지점 -> 300m 지점 = 300m 남짓. 출발점을 빼면 200m 남짓이 나온다.
        assertThat(response.totalDistance()).isBetween(290, 310);
        assertThat(response.legs()).isEmpty();
    }

    /** 목록의 추정 거리도 상세처럼 출발점부터 센다. 가게 수에는 출발점을 넣지 않는다. */
    @Test
    void 목록도_출발점부터_센다() {
        UUID userId = UUID.randomUUID();
        SavedCourse withStart = savedCourse(userId, CENTER_LAT, CENTER_LNG, store(1), store(3));
        SavedCourse legacy = savedCourse(userId, null, null, store(1), store(3));
        given(savedCourseRepository.findByUser_UserIdAndDemoOrderByCreatedAtDesc(userId, false))
                .willReturn(List.of(withStart, legacy));

        var courses = courseService.findMyCourses(userId, false);

        assertThat(courses.get(0).totalDistance()).isBetween(290, 310);
        assertThat(courses.get(0).storeCount()).isEqualTo(2);
        // 옛 코스는 첫 가게에서 출발하므로 예전 값(가게 사이 거리) 그대로다.
        assertThat(courses.get(1).totalDistance()).isBetween(190, 210);
    }

    /** 추천 응답을 그대로 돌려보내도 저장된다 — 출발점 nil UUID는 건너뛰고 좌표는 코스에 남긴다. */
    @Test
    void 저장은_출발점_id를_건너뛰고_좌표를_남긴다() {
        UUID userId = UUID.randomUUID();
        Store first = store(1);
        Store second = store(3);
        given(storeRepository.findAllById(any())).willReturn(List.of(first, second));
        given(userRepository.getReferenceById(userId)).willReturn(org.mockito.Mockito.mock(User.class));
        given(savedCourseRepository.save(any())).willAnswer(call -> call.getArgument(0));

        courseService.save(userId, saveRequest(CENTER_LAT, CENTER_LNG,
                CourseStoreResponse.START_POINT_ID, first.getStoreId(), second.getStoreId()), false);

        org.mockito.ArgumentCaptor<SavedCourse> captor = org.mockito.ArgumentCaptor.forClass(SavedCourse.class);
        org.mockito.Mockito.verify(savedCourseRepository).save(captor.capture());
        SavedCourse saved = captor.getValue();
        assertThat(saved.getStartLat()).isEqualTo(CENTER_LAT);
        assertThat(saved.getStartLng()).isEqualTo(CENTER_LNG);
        // DB의 arrival_order는 가게만 1부터 센다.
        assertThat(saved.getStores())
                .extracting(s -> s.getStore().getStoreId(), s -> s.getArrivalOrder())
                .containsExactly(tuple(first.getStoreId(), 1), tuple(second.getStoreId(), 2));
    }

    /** 좌표가 한쪽만 오면 둘 다 없는 것으로 본다 — 반쪽 좌표로는 출발점을 만들 수 없다. */
    @Test
    void 좌표가_한쪽만_오면_출발점을_남기지_않는다() {
        UUID userId = UUID.randomUUID();
        Store first = store(1);
        given(storeRepository.findAllById(any())).willReturn(List.of(first));
        given(savedCourseRepository.save(any())).willAnswer(call -> call.getArgument(0));

        courseService.save(userId, saveRequest(CENTER_LAT, null, first.getStoreId()), false);

        org.mockito.ArgumentCaptor<SavedCourse> captor = org.mockito.ArgumentCaptor.forClass(SavedCourse.class);
        org.mockito.Mockito.verify(savedCourseRepository).save(captor.capture());
        assertThat(captor.getValue().getStartLat()).isNull();
        assertThat(captor.getValue().getStartLng()).isNull();
    }

    @Test
    void 출발점_id만_있으면_저장하지_않는다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> courseService.save(UUID.randomUUID(),
                        saveRequest(CENTER_LAT, CENTER_LNG, CourseStoreResponse.START_POINT_ID), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private com.server.walwang_be.dto.CourseSaveRequest saveRequest(Double startLat, Double startLng,
                                                                    UUID... storeIds) {
        return new com.server.walwang_be.dto.CourseSaveRequest(
                "코스", DogSize.SMALL_MEDIUM, List.of(CoursePurpose.CAFE), CourseDuration.HALF_DAY,
                null, List.of(storeIds), startLat, startLng);
    }

    private void assertThatSameCourse(CourseResponse actual, CourseResponse expected) {
        assertThat(actual.totalDistance()).isEqualTo(expected.totalDistance());
        assertThat(actual.totalDuration()).isEqualTo(expected.totalDuration());
        assertThat(actual.legs()).isEqualTo(expected.legs());
        assertThat(actual.stores()).hasSameSizeAs(expected.stores());
        assertThat(actual.stores().get(0)).isEqualTo(expected.stores().get(0));
    }

    @SuppressWarnings("unchecked")
    private List<List<TmapClient.Waypoint>> capturedWaypoints() {
        org.mockito.ArgumentCaptor<List<TmapClient.Waypoint>> captor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(tmapClient, org.mockito.Mockito.atLeastOnce())
                .walkRoute(captor.capture());
        return captor.getAllValues();
    }

    private void givenNearby(Store... stores) {
        List<Store> nearby = List.of(stores);
        given(storeRepository.findWithinRadius(anyDouble(), anyDouble(), anyInt()))
                .willReturn(nearby);
        given(tourVerifier.within(anyDouble(), anyDouble(), anyInt())).willReturn(new TourVerifier.Scope(
                nearby, nearby.stream().map(Store::getStoreId).collect(Collectors.toSet()), nearby.size()));
    }

    private void givenSavedCourse(UUID userId, UUID courseId, Double startLat, Double startLng,
                                  Store... stores) {
        SavedCourse course = savedCourse(userId, startLat, startLng, stores);
        set(course, "courseId", courseId);

        given(savedCourseRepository.findWithStoresByCourseId(courseId))
                .willReturn(Optional.of(course));
    }

    private SavedCourse savedCourse(UUID userId, Double startLat, Double startLng, Store... stores) {
        User user = org.mockito.Mockito.mock(User.class);
        given(user.getUserId()).willReturn(userId);

        SavedCourse course = SavedCourse.builder()
                .user(user)
                .title("저장한 코스")
                .dogSize(DogSize.SMALL_MEDIUM)
                .purposes(List.of(CoursePurpose.CAFE))
                .duration(CourseDuration.HALF_DAY)
                .description(null)
                .startLat(startLat)
                .startLng(startLng)
                .build();
        for (int i = 0; i < stores.length; i++) {
            course.addStore(stores[i], i + 1);
        }
        return course;
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
