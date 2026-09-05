package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml 의 ai.* 설정.
 *
 * ai:
 *   base-url: http://localhost:8000     # 비워두면 스텁이 대신 동작한다
 *   ocr-timeout: 20s
 *   stamp-timeout: 8s
 *   embedding-timeout: 10s
 *   query-timeout: 3s
 *
 * 타임아웃을 나눠 둔 이유는 성격이 달라서다 — OCR은 사용자가 화면에서 기다리는
 * 요청이라 짧아야 한다. 도장 생성도 이제 같다 — 리뷰 등록 응답에 최종 도장을
 * 실어 보내느라 사용자가 그 시간만큼 기다린다. 그래서 120초에서 8초로 줄였다.
 * 8초 안에 못 받으면 기본 발도장으로 확정하고 리뷰는 그대로 저장된다.
 * 임베딩 갱신은 실패해도 다음 리뷰가 대신 갱신해 주므로 짧게 끊는다.
 * 기준 벡터는 추천 요청 한가운데서 나가는 호출이라 가장 짧다 — 여기서 지체되면
 * 사용자가 그만큼 빈 화면을 본다. 못 받으면 거리순 폴백으로 간다.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        String baseUrl,
        Duration ocrTimeout,
        Duration stampTimeout,
        Duration embeddingTimeout,
        Duration queryTimeout
) {
    private static final Duration DEFAULT_OCR_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_STAMP_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_EMBEDDING_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(3);

    public Duration ocrTimeoutOrDefault() {
        return ocrTimeout == null ? DEFAULT_OCR_TIMEOUT : ocrTimeout;
    }

    public Duration stampTimeoutOrDefault() {
        return stampTimeout == null ? DEFAULT_STAMP_TIMEOUT : stampTimeout;
    }

    public Duration embeddingTimeoutOrDefault() {
        return embeddingTimeout == null ? DEFAULT_EMBEDDING_TIMEOUT : embeddingTimeout;
    }

    public Duration queryTimeoutOrDefault() {
        return queryTimeout == null ? DEFAULT_QUERY_TIMEOUT : queryTimeout;
    }
}
