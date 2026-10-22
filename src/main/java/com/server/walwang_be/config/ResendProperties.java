package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml 의 resend.* 설정. 인증 메일 발송(Resend).
 *
 * resend:
 *   api-key: ${RESEND_API_KEY:}
 *   from: ${MAIL_FROM:왈왕 <no-reply@send.walwang.site>}
 *   stub-log-code: ${MAIL_STUB_LOG_CODE:false}
 *
 * api-key 뒤의 콜론을 빠뜨리면 환경변수가 없을 때 앱이 뜨지 않는다.
 * 키가 비어 있으면 발송하지 않는 스텁으로 돈다(ResendEmailSender).
 *
 * 발신 주소는 루트(walwang.site)가 아니라 send. 서브도메인이다 — 루트에는 privacy@ 수신용
 * Cloudflare Email Routing의 SPF가 걸려 있어 Resend SPF를 함께 두면 충돌한다.
 *
 * api-key는 절대 로그에 남기지 않는다.
 */
@ConfigurationProperties(prefix = "resend")
public record ResendProperties(
        String baseUrl,
        String apiKey,
        String from,
        Boolean stubLogCode,
        Duration timeout
) {
    private static final String DEFAULT_BASE_URL = "https://api.resend.com";
    private static final String DEFAULT_FROM = "왈왕 <no-reply@send.walwang.site>";
    /** 사용자가 화면에서 기다리는 요청이라 길게 잡지 않는다. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public String baseUrlOrDefault() {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    public String fromOrDefault() {
        return from == null || from.isBlank() ? DEFAULT_FROM : from;
    }

    public Duration timeoutOrDefault() {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 스텁이 인증 코드를 로그에 찍어도 되는지.
     *
     * 키가 있으면 플래그와 상관없이 언제나 false다 — 운영에서 실수로 플래그를 켜도
     * 코드가 운영 로그로 새지 않게 하려는 것이다. 키가 없을 때(개발)만 플래그를 본다.
     */
    public boolean logsCodeInStub() {
        return !configured() && Boolean.TRUE.equals(stubLogCode);
    }
}
