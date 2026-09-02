package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml 의 ai.* 설정.
 *
 * ai:
 *   base-url: http://localhost:8000     # 비워두면 스텁이 대신 동작한다
 *   ocr-timeout: 20s
 *   stamp-timeout: 120s
 *
 * 타임아웃을 나눠 둔 이유는 성격이 달라서다 — OCR은 사용자가 화면에서 기다리는
 * 요청이라 짧아야 하고, 도장 생성은 백그라운드라 오래 걸려도 된다.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        String baseUrl,
        Duration ocrTimeout,
        Duration stampTimeout
) {
    private static final Duration DEFAULT_OCR_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_STAMP_TIMEOUT = Duration.ofSeconds(120);

    public Duration ocrTimeoutOrDefault() {
        return ocrTimeout == null ? DEFAULT_OCR_TIMEOUT : ocrTimeout;
    }

    public Duration stampTimeoutOrDefault() {
        return stampTimeout == null ? DEFAULT_STAMP_TIMEOUT : stampTimeout;
    }
}
