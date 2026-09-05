package com.server.walwang_be.service;

import com.server.walwang_be.client.AiEmbeddingClient;
import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.config.AiProperties;
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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 목 AI를 실제로 띄워 놓고 코스를 뽑아 본다.
 *
 * 앞의 CourseServiceRankingTest는 EmbeddingService까지 목으로 세우지만,
 * 여기서는 HTTP -> 클라이언트 -> 서비스 -> 코스까지 한 줄로 이어 붙인 채 돌린다.
 * 같은 요청에 태그만 넣고 뺐을 때 코스가 실제로 달라지는지를 보려는 것이다.
 *
 * pgvector 질의만 목이다 — 벡터 연산은 DB가 있어야 돌아간다.
 * 대신 "AI가 이 순서로 답했다"를 그 자리에 놓고, 그 순서가 코스에 반영되는지 본다.
 */
class CourseRecommendWithMockAiTest {

    private static final double CENTER_LAT = 37.5445;
    private static final double CENTER_LNG = 127.0557;
    /** 위도 0.0009도 ≈ 100m */
    private static final double DEGREE_PER_100M = 0.0009;

    private HttpServer aiServer;
    private final AtomicInteger aiCalls = new AtomicInteger();
    private final AtomicReference<String> lastAiBody = new AtomicReference<>();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlaceEmbeddingRepository placeEmbeddingRepository = mock(PlaceEmbeddingRepository.class);
    private final TmapClient tmapClient = mock(TmapClient.class);
    private final NearbyPlaceFinder nearbyPlaceFinder = mock(NearbyPlaceFinder.class);
    private final TourVerifier tourVerifier = mock(TourVerifier.class);
    private final StoreCandidates storeCandidates = mock(StoreCandidates.class);

    private CourseService courseService;

    /** 지도 중심에서 100m / 500m / 1000m 떨어진 세 곳. */
    private Store near;
    private Store mid;
    private Store far;

    @BeforeEach
    void setUp() throws IOException {
        startMockAi();

        near = store(1);
        mid = store(5);
        far = store(10);
        List<Store> nearby = List.of(near, mid, far);

        given(storeRepository.findWithinRadius(anyDouble(), anyDouble(), anyInt())).willReturn(nearby);
        given(tourVerifier.verifiedAmong(any())).willReturn(
                nearby.stream().map(Store::getStoreId).collect(Collectors.toSet()));
        given(storeCandidates.statuses(any(), any(), anyBoolean())).willReturn(Map.of());
        given(storeCandidates.hasEvidence(any(), any(), any())).willReturn(true);
        given(storeCandidates.tags(any(), anyBoolean())).willReturn(Map.of());
        given(storeCandidates.toResponses(any(), any(), any(), anyBoolean())).willReturn(List.of());
        given(nearbyPlaceFinder.around(any())).willReturn(List.of());
        given(tmapClient.walkRoute(any())).willReturn(Optional.empty());

        given(placeEmbeddingRepository.existsAny(any(), eq(false))).willReturn(true);
        // AI가 보기에는 먼 곳이 가장 잘 맞는다 — 거리순과 정반대로 답하게 둔다.
        given(placeEmbeddingRepository.rankBySimilarity(any(), any(), anyBoolean()))
                .willReturn(List.of(far.getStoreId(), mid.getStoreId(), near.getStoreId()));

        String baseUrl = "http://127.0.0.1:" + aiServer.getAddress().getPort();
        EmbeddingService embeddingService = new EmbeddingService(
                new AiEmbeddingClient(new AiProperties(baseUrl, null, null, null, null)));

        courseService = new CourseService(
                storeRepository,
                mock(StoreSizeStatusRepository.class),
                mock(StoreTagCountRepository.class),
                mock(SavedCourseRepository.class),
                mock(UserRepository.class),
                placeEmbeddingRepository,
                tmapClient,
                nearbyPlaceFinder,
                tourVerifier,
                storeCandidates,
                embeddingService);
    }

    @AfterEach
    void stopMockAi() {
        // AI 장애 테스트가 이미 내렸을 수 있다.
        if (aiServer != null) {
            aiServer.stop(0);
        }
    }

    @Test
    void 태그가_있으면_AI가_고른_곳으로_코스가_바뀐다() {
        courseService.recommend(request(List.of("QUIET", "NOT_CROWDED")), false);

        assertThat(aiCalls.get()).isEqualTo(1);
        assertThat(lastAiBody.get()).isEqualTo("{\"tags\":[\"QUIET\",\"NOT_CROWDED\"]}");
        // AI가 꼽은 far, mid가 담기고, 걷는 순서만 가까운 쪽부터로 정리된다.
        assertThat(route()).containsExactly(mid, far);
    }

    @Test
    void 태그가_없으면_AI를_부르지_않고_거리순_그대로다() {
        courseService.recommend(request(null), false);

        assertThat(aiCalls.get()).isZero();
        assertThat(route()).containsExactly(near, mid);
    }

    @Test
    void 태그가_비어도_AI를_부르지_않는다() {
        courseService.recommend(request(List.of()), false);

        assertThat(aiCalls.get()).isZero();
        assertThat(route()).containsExactly(near, mid);
    }

    /**
     * AI가 죽어 있을 때.
     *
     * 추천이 실패로 끝나면 안 된다 — 태그를 넣지 않은 것과 같은 코스가 나와야 한다.
     */
    @Test
    void AI가_장애면_거리순으로_내려앉는다() {
        aiServer.stop(0);
        aiServer = null;

        courseService.recommend(request(List.of("QUIET")), false);

        assertThat(route()).containsExactly(near, mid);
    }

    /** 코스에 실제로 담긴 가게를 방문 순서대로. */
    private List<Store> route() {
        ArgumentCaptor<List<Store>> captor = ArgumentCaptor.captor();
        verify(storeCandidates).toResponses(captor.capture(), any(), any(), anyBoolean());
        return captor.getValue();
    }

    private CourseRecommendRequest request(List<String> tags) {
        return new CourseRecommendRequest(
                DogSize.SMALL_MEDIUM, List.of(CoursePurpose.CAFE), CourseDuration.SHORT,
                CENTER_LAT, CENTER_LNG, tags);
    }

    private void startMockAi() throws IOException {
        aiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        aiServer.createContext("/internal/embedding/query", exchange -> {
            aiCalls.incrementAndGet();
            try (InputStream in = exchange.getRequestBody()) {
                lastAiBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = embeddingJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        aiServer.start();
    }

    /** 1536차원이기만 하면 된다. 어느 쪽이 가까운지는 pgvector 자리(목)가 정한다. */
    private String embeddingJson() {
        StringBuilder json = new StringBuilder("{\"embedding\":[");
        for (int i = 0; i < com.server.walwang_be.client.EmbeddingClient.DIMENSION; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("0.01");
        }
        return json.append("]}").toString();
    }

    private Store store(int hundredMeters) {
        Store store = Store.builder()
                .externalId(UUID.randomUUID().toString())
                .name("가게")
                .lat(CENTER_LAT + hundredMeters * DEGREE_PER_100M)
                .lng(CENTER_LNG)
                .address("서울 성동구")
                .type("cafe")
                .build();
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
