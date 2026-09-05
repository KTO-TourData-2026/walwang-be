package com.server.walwang_be.service;

import com.server.walwang_be.client.EmbeddingClient;
import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.dto.CourseRecommendRequest;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.PlaceEmbeddingRepository;
import com.server.walwang_be.repo.SavedCourseRepository;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import com.server.walwang_be.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 기준 벡터가 pgvector 질의까지 흘러가는지, 그리고 그 결과가 실제로 코스를 바꾸는지 본다.
 *
 * 벡터 연산 자체(코사인 거리)는 pgvector가 붙은 DB에서만 돌아가므로 여기서 확인하지 않는다.
 * 여기서 잡으려는 건 배선이다 — 태그가 들어오면 AI를 부르는지, 받은 벡터를
 * rankBySimilarity에 넘기는지, 그 순서대로 코스가 짜이는지, 못 받았을 때 거리순으로 내려앉는지.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CourseServiceRankingTest {

    private static final double CENTER_LAT = 37.5445;
    private static final double CENTER_LNG = 127.0557;
    /** 위도 0.001도 ≈ 111m */
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
        given(storeCandidates.toResponses(any(), any(), any(), anyBoolean())).willReturn(List.of());
        given(nearbyPlaceFinder.around(any())).willReturn(List.of());
        // 티맵 없이 간다. 경로선만 비고 코스 자체는 그대로 나온다.
        given(tmapClient.walkRoute(any())).willReturn(Optional.empty());
    }

    @Test
    void 태그가_있으면_받아온_벡터로_유사도_정렬을_부른다() {
        Store near = store(1);
        Store far = store(10);
        givenNearby(near, far);

        float[] queryVector = new float[EmbeddingClient.DIMENSION];
        queryVector[0] = 0.42f;
        List<String> tags = List.of("QUIET", "NOT_CROWDED");

        given(embeddingService.queryEmbedding(tags)).willReturn(queryVector);
        given(placeEmbeddingRepository.existsAny(any(), eq(false))).willReturn(true);
        givenRanking(far, near);

        courseService.recommend(request(tags, CourseDuration.SHORT), false);

        ArgumentCaptor<float[]> sent = ArgumentCaptor.forClass(float[].class);
        verify(placeEmbeddingRepository).rankBySimilarity(sent.capture(), any(), eq(false));
        assertThat(sent.getValue()).isSameAs(queryVector);
    }

    /**
     * 유사도 정렬이 코스에 실제로 반영되는지.
     *
     * 셋 다 걸어갈 만한 거리에 있으니 예산은 걸리지 않는다.
     * 그런데도 가장 가까운 곳이 빠진다면, 그건 AI가 고른 두 곳이 대신 들어갔다는 뜻이다.
     */
    @Test
    void 유사도_상위부터_코스에_담는다() {
        Store near = store(1);
        Store mid = store(5);
        Store far = store(10);
        givenNearby(near, mid, far);

        given(embeddingService.queryEmbedding(any()))
                .willReturn(new float[EmbeddingClient.DIMENSION]);
        given(placeEmbeddingRepository.existsAny(any(), eq(false))).willReturn(true);
        givenRanking(far, mid, near);

        // SHORT는 두 곳까지만 묶는다.
        courseService.recommend(request(List.of("QUIET"), CourseDuration.SHORT), false);

        // 고른 곳은 far, mid. 걷는 순서는 가까운 쪽부터라 mid -> far 가 된다.
        assertThat(route()).containsExactly(mid, far);
    }

    /**
     * 예산을 넘기는 후보는 건너뛰고 다음 후보로 넘어간다.
     *
     * far 하나로 2.8km를 쓴 뒤라, 반대쪽에 있는 곳을 더하면 왕복이 되어 3km를 넘는다.
     * 그래서 유사도 2·3등이 모두 밀리고 코스는 한 곳으로 끝난다 —
     * 걸을 수 없는 코스를 주느니 짧은 코스를 준다.
     */
    @Test
    void 도보_예산을_넘기면_그_후보를_건너뛴다() {
        Store far = store(28);
        Store oppositeMid = store(-8);
        Store oppositeNear = store(-3);
        givenNearby(far, oppositeMid, oppositeNear);

        given(embeddingService.queryEmbedding(any()))
                .willReturn(new float[EmbeddingClient.DIMENSION]);
        given(placeEmbeddingRepository.existsAny(any(), eq(false))).willReturn(true);
        givenRanking(far, oppositeMid, oppositeNear);

        courseService.recommend(request(List.of("QUIET"), CourseDuration.SHORT), false);

        assertThat(route()).containsExactly(far);
    }

    @Test
    void 벡터를_못_받으면_거리순으로_내려앉는다() {
        Store near = store(1);
        Store mid = store(5);
        Store far = store(10);
        givenNearby(near, mid, far);

        // 태그가 없거나 AI가 죽었을 때 모두 이 길로 온다.
        given(embeddingService.queryEmbedding(any())).willReturn(null);
        given(placeEmbeddingRepository.existsAny(any(), eq(false))).willReturn(true);

        courseService.recommend(request(null, CourseDuration.SHORT), false);

        verify(placeEmbeddingRepository, never()).rankBySimilarity(any(), any(), anyBoolean());
        // 예산도 보지 않는다 — 태그 없는 요청은 이 기능이 붙기 전과 똑같이 돌아야 한다.
        assertThat(route()).containsExactly(near, mid);
    }

    @Test
    void 임베딩이_하나도_없으면_AI를_부르지도_않는다() {
        givenNearby(store(1), store(5));
        given(placeEmbeddingRepository.existsAny(any(), eq(false))).willReturn(false);

        courseService.recommend(request(List.of("QUIET"), CourseDuration.SHORT), false);

        verify(embeddingService, never()).queryEmbedding(any());
        verify(placeEmbeddingRepository, never()).rankBySimilarity(any(), any(), anyBoolean());
    }

    /** 코스에 실제로 담긴 가게를 방문 순서대로. */
    private List<Store> route() {
        ArgumentCaptor<List<Store>> captor = ArgumentCaptor.captor();
        verify(storeCandidates).toResponses(captor.capture(), any(), any(), anyBoolean());
        return captor.getValue();
    }

    private void givenNearby(Store... stores) {
        List<Store> nearby = List.of(stores);
        given(storeRepository.findWithinRadius(anyDouble(), anyDouble(), anyInt())).willReturn(nearby);
        given(tourVerifier.verifiedAmong(any())).willReturn(
                nearby.stream().map(Store::getStoreId).collect(Collectors.toSet()));
    }

    /** AI가 이 순서로 잘 맞는다고 답했다고 둔다. */
    private void givenRanking(Store... ranked) {
        given(placeEmbeddingRepository.rankBySimilarity(any(), any(), anyBoolean()))
                .willReturn(List.of(ranked).stream().map(Store::getStoreId).toList());
    }

    private CourseRecommendRequest request(List<String> tags, CourseDuration duration) {
        return new CourseRecommendRequest(
                DogSize.SMALL_MEDIUM, List.of(CoursePurpose.CAFE), duration,
                CENTER_LAT, CENTER_LNG, tags);
    }

    /** 지도 중심에서 남북으로 떨어진 가게. 단위는 100m 남짓이고, 음수면 반대쪽이다. */
    private Store store(int hundredMeters) {
        Store store = Store.builder()
                .externalId(UUID.randomUUID().toString())
                .name("가게")
                .lat(CENTER_LAT + hundredMeters * DEGREE_PER_100M)
                .lng(CENTER_LNG)
                .address("서울 성동구")
                .type("cafe")
                .build();
        // storeId는 DB가 채우는 값이라 빌더로는 넣을 수 없다.
        setStoreId(store, UUID.randomUUID());
        return store;
    }

    private void setStoreId(Store store, UUID storeId) {
        try {
            var field = Store.class.getDeclaredField("storeId");
            field.setAccessible(true);
            field.set(store, storeId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
