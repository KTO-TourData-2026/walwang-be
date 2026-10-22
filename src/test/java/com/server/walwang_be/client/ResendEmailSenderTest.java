package com.server.walwang_be.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.server.walwang_be.config.ResendProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인증 코드·API 키·받는 주소가 로그로 새지 않는지 본다.
 * 스텁이 코드를 찍는 것은 "키 없음 + 플래그 켬"일 때 하나뿐이어야 한다.
 */
class ResendEmailSenderTest {

    private static final String TO = "dog@example.com";
    private static final String CODE = "482913";
    private static final String KEY = "re_test_secret_key";
    private static final Duration VALIDITY = Duration.ofMinutes(5);

    private final Logger logger = (Logger) LoggerFactory.getLogger(ResendEmailSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void 코드를_찍는_것은_키가_없고_플래그를_켰을_때뿐이다() {
        assertThat(properties(null, true).logsCodeInStub()).isTrue();
        assertThat(properties(null, false).logsCodeInStub()).isFalse();
        assertThat(properties(null, null).logsCodeInStub()).isFalse();
        assertThat(properties("", true).logsCodeInStub()).isTrue();
        // 운영에서 실수로 켜도 키가 있으면 무시한다.
        assertThat(properties(KEY, true).logsCodeInStub()).isFalse();
        assertThat(properties(KEY, false).logsCodeInStub()).isFalse();
    }

    @Test
    void 스텁은_플래그가_꺼져_있으면_코드_없이_WARN만_남긴다() {
        ResendEmailSender sender = sender(properties(null, false));
        sender.warnIfStub();
        sender.sendVerificationCode(TO, CODE, VALIDITY);

        assertThat(logs()).isNotEmpty().noneMatch(line -> line.contains(CODE) || line.contains(TO));
    }

    @Test
    void 스텁은_플래그를_켜면_코드를_찍는다() {
        sender(properties(null, true)).sendVerificationCode(TO, CODE, VALIDITY);

        assertThat(logs()).anyMatch(line -> line.contains(CODE));
        assertThat(logs()).noneMatch(line -> line.contains(TO));
    }

    @Test
    void 키가_있으면_플래그를_켜도_시작_로그와_실패_로그에_코드_키_주소가_없다() {
        // 닫힌 포트로 보내 연결 실패를 일으킨다. 실제 Resend로는 나가지 않는다.
        ResendProperties properties = new ResendProperties(
                "http://127.0.0.1:1", KEY, null, true, Duration.ofSeconds(2));
        ResendEmailSender sender = sender(properties);
        sender.warnIfStub();

        assertThatThrownBy(() -> sender.sendVerificationCode(TO, CODE, VALIDITY))
                .isInstanceOf(EmailSender.MailUnavailableException.class);

        assertThat(logs()).isNotEmpty()
                .noneMatch(line -> line.contains(CODE) || line.contains(KEY) || line.contains(TO));
    }

    @Test
    void 본문에는_코드와_유효시간만_담는다() {
        String html = ResendEmailSender.verificationHtml(CODE, VALIDITY);

        assertThat(html).contains(CODE).contains("5분");
        assertThat(html).doesNotContain(TO);
    }

    private ResendProperties properties(String apiKey, Boolean stubLogCode) {
        return new ResendProperties(null, apiKey, null, stubLogCode, null);
    }

    private ResendEmailSender sender(ResendProperties properties) {
        return new ResendEmailSender(properties, JsonMapper.builder().build());
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
