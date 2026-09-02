package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml 의 tmap.* 설정.
 *
 * tmap:
 *   app-key: ${TMAP_APP_KEY}
 *
 * app-key 값은 파일에 쓰지 않는다. 환경변수로만 주입한다.
 */
@ConfigurationProperties(prefix = "tmap")
public record TmapProperties(
        String appKey,
        String baseUrl,
        Duration timeout
) {
    private static final String DEFAULT_BASE_URL = "https://apis.openapi.sk.com";
    /** 코스 추천이 사용자를 기다리게 하는 요청이라 짧게 잡는다. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public String baseUrlOrDefault() {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    public Duration timeoutOrDefault() {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public boolean configured() {
        return appKey != null && !appKey.isBlank();
    }
}
