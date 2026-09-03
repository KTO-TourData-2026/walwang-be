package com.server.walwang_be.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.server.walwang_be.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * POST /internal/embedding/refresh 와 /internal/embedding/query 를 호출한다.
 *
 * 타임아웃이 달라 RestClient를 둘로 나눈다 — 갱신은 뒤에서 도는 일이라 여유가 있지만,
 * 기준 벡터는 사용자가 추천 결과를 기다리는 동안 나가는 호출이라 짧게 끊어야 한다.
 */
@Slf4j
public class AiEmbeddingClient implements EmbeddingClient {

    private final RestClient refreshClient;
    private final RestClient queryClient;

    public AiEmbeddingClient(AiProperties properties) {
        this.refreshClient = build(properties.baseUrl(), properties.embeddingTimeoutOrDefault());
        this.queryClient = build(properties.baseUrl(), properties.queryTimeoutOrDefault());
    }

    private RestClient build(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public void refresh(UUID storeId) {
        try {
            // AI와 주고받는 규약은 snake_case다. 응답의 status는 읽지 않는다 —
            // 성공 여부는 상태코드로 충분하고, 우리가 받아 쓸 값이 없다.
            refreshClient.post()
                    .uri("/internal/embedding/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("store_id", storeId.toString()))
                    .retrieve()
                    // 404는 "그런 가게가 없다"는 뜻이라 장애와 구분해서 올린다.
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                        throw new EmbeddingStoreNotFoundException(
                                "임베딩 갱신 대상 가게를 찾지 못했습니다: " + storeId);
                    })
                    .body(RefreshResponse.class);

        } catch (EmbeddingStoreNotFoundException e) {
            throw e;
        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("임베딩 갱신 서비스에 연결하지 못했습니다.", e);
        }
    }

    @Override
    public Optional<float[]> query(List<String> tags) {
        List<String> codes = tags == null ? List.of() : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .toList();
        // 빈 태그로 부르면 AI는 400을 준다. 물어볼 것이 없으니 나가지 않는다.
        if (codes.isEmpty()) {
            return Optional.empty();
        }

        try {
            QueryResponse response = queryClient.post()
                    .uri("/internal/embedding/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tags", codes))
                    .retrieve()
                    .body(QueryResponse.class);

            float[] embedding = response == null ? null : response.embedding();
            if (embedding == null || embedding.length != DIMENSION) {
                // 길이가 어긋난 벡터를 그대로 넘기면 pgvector가 질의에서 터진다.
                // 추천 전체를 죽이느니 벡터가 없는 셈 치고 거리순으로 간다.
                log.warn("기준 벡터 차원이 맞지 않습니다: {} (기대 {})",
                        embedding == null ? "없음" : embedding.length, DIMENSION);
                return Optional.empty();
            }
            return Optional.of(embedding);

        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("기준 벡터를 받지 못했습니다.", e);
        }
    }

    private record RefreshResponse(@JsonProperty("status") String status) {
    }

    private record QueryResponse(@JsonProperty("embedding") float[] embedding) {
    }
}
