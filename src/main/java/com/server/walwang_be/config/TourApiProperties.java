package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml 의 tour.* 설정. 공공데이터포털 반려동물 동반여행 API(15135102).
 *
 * tour:
 *   service-key: <포털의 일반 인증키(Encoding)>
 *   mobile-app: walwang
 *
 * 서비스 이름 끝의 2를 빠뜨리면(KorPetTourService) NO_OPENAPI_SERVICE_ERROR가 돌아온다.
 * 오퍼레이션뿐 아니라 서비스 이름에도 2가 붙는다.
 *
 * service-key는 절대 로그에 남기지 않는다.
 */
@ConfigurationProperties(prefix = "tour")
public record TourApiProperties(
        String baseUrl,
        String serviceKey,
        String mobileApp,
        Duration timeout
) {
    private static final String DEFAULT_BASE_URL =
            "https://apis.data.go.kr/B551011/KorPetTourService2";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public String baseUrlOrDefault() {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    public String mobileAppOrDefault() {
        return mobileApp == null || mobileApp.isBlank() ? "walwang" : mobileApp;
    }

    public Duration timeoutOrDefault() {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public boolean configured() {
        return serviceKey != null && !serviceKey.isBlank();
    }
}
