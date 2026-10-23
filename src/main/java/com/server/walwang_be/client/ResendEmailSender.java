package com.server.walwang_be.client;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import com.server.walwang_be.config.ResendProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resend(https://resend.com/docs/api-reference/emails/send-email)로 인증 메일을 보낸다.
 *
 * 키가 없으면 스텁으로 돈다 — 보내지 않고 WARN만 남긴다. 코드는 호출한 쪽이 이미 Redis에
 * 저장했으므로 개발 환경에서도 인증 흐름을 끝까지 밟아볼 수 있다.
 * 스텁이 코드를 로그에 찍는 것은 resend.stub-log-code를 켰을 때뿐이고, 키가 있으면 그마저
 * 무시한다(ResendProperties.logsCodeInStub).
 *
 * 로그에는 키·인증 코드·받는 주소·렌더링한 본문을 남기지 않는다. Resend의 에러 본문은 받는 주소를
 * 되울려 줄 수 있어 TourApiClient와 달리 본문을 통째로 찍지 않고 상태와 에러 이름만 남긴다.
 *
 * 본문은 templates/mail/의 Mustache 템플릿 두 벌(HTML·평문)이다. 관리자 화면과 같은 jmustache를
 * 쓰지만 뷰 리졸버를 거치지 않고 여기서 직접 읽는다. 생성자에서 컴파일하므로 템플릿이 없거나
 * 깨져 있으면 첫 발송이 아니라 기동할 때 실패한다.
 */
@Slf4j
@Component
public class ResendEmailSender implements EmailSender {

    /** 코드를 넣지 않는다. 제목은 알림 미리보기·메일 목록에 그대로 드러난다. */
    static final String SUBJECT = "[왈왕] 이메일 인증 코드";

    private static final String HTML_TEMPLATE = "templates/mail/verification-email.mustache";
    private static final String TEXT_TEMPLATE = "templates/mail/verification-email.txt.mustache";

    private final ResendProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Template htmlTemplate;
    private final Template textTemplate;

    public ResendEmailSender(ResendProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        long timeoutMillis = properties.timeoutOrDefault().toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeoutMillis);
        factory.setReadTimeout((int) timeoutMillis);
        this.restClient = RestClient.builder().requestFactory(factory).build();

        // 값이 비면 빈칸으로 넘기지 않고 예외를 낸다(jmustache 기본값) — 코드 없는 메일이 나가는 것보다 낫다.
        this.htmlTemplate = compile(Mustache.compiler(), HTML_TEMPLATE);
        // 평문에는 HTML 이스케이프가 맞지 않는다. 지금은 숫자뿐이라 결과가 같지만 엉뚱한 &amp;가 섞이지 않게 끈다.
        this.textTemplate = compile(Mustache.compiler().escapeHTML(false), TEXT_TEMPLATE);
    }

    /** 스텁으로 뜨는 것을 조용히 넘기지 않는다. 운영에서 키가 빠지면 아무도 가입하지 못한다. */
    @PostConstruct
    void warnIfStub() {
        if (properties.configured()) {
            if (Boolean.TRUE.equals(properties.stubLogCode())) {
                log.warn("RESEND_API_KEY가 설정돼 있어 resend.stub-log-code를 무시합니다. 인증 코드는 로그에 남지 않습니다.");
            }
            return;
        }
        log.warn("RESEND_API_KEY가 없어 인증 메일을 보내지 않습니다(스텁). 운영이라면 환경변수를 확인하세요.");
        if (properties.logsCodeInStub()) {
            log.warn("resend.stub-log-code가 켜져 있어 스텁이 인증 코드를 로그에 남깁니다. 개발 환경에서만 켜세요.");
        }
    }

    @Override
    public void sendVerificationCode(String to, String code) {
        if (!properties.configured()) {
            if (properties.logsCodeInStub()) {
                log.warn("[메일 스텁] 발송을 건너뜁니다. 인증 코드: {}", code);
            } else {
                log.warn("[메일 스텁] 발송을 건너뜁니다. 코드는 Redis에만 저장돼 있습니다.");
            }
            return;
        }

        try {
            restClient.post()
                    .uri(properties.baseUrlOrDefault() + "/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(requestBody(to, code)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("=== RESEND ERROR status={} name={} ===", e.getStatusCode(), errorName(e));
            throw new MailUnavailableException("인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (RestClientException e) {
            // 연결 실패·타임아웃 등. 예외 메시지에 무엇이 섞일지 몰라 종류만 남긴다.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.warn("=== RESEND CALL FAILED {} ===", root.getClass().getName());
            throw new MailUnavailableException("인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    /** Resend로 나가는 본문 전체. 여기 없는 필드(tags 등)는 보내지 않는다. */
    Map<String, Object> requestBody(String to, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", properties.fromOrDefault());
        body.put("to", List.of(to));
        body.put("subject", SUBJECT);
        body.put("html", renderHtml(code));
        body.put("text", renderText(code));
        return body;
    }

    String renderHtml(String code) {
        return htmlTemplate.execute(Map.of("code", code));
    }

    String renderText(String code) {
        return textTemplate.execute(Map.of("code", code));
    }

    private static Template compile(Mustache.Compiler compiler, String path) {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            return compiler.compile(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("메일 템플릿을 읽지 못했습니다: " + path, e);
        }
    }

    /** Resend 에러 본문의 name(예: validation_error). 본문의 나머지는 받는 주소가 섞일 수 있어 버린다. */
    private String errorName(RestClientResponseException e) {
        try {
            return objectMapper.readTree(e.getResponseBodyAsString()).path("name").asString("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
