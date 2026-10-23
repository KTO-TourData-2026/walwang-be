package com.server.walwang_be.service;

import com.server.walwang_be.client.EmailSender;
import com.server.walwang_be.repo.EmailVerificationRepository;
import com.server.walwang_be.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 가입 이메일 인증. 막아야 할 때 실제로 아무것도 보내지 않는지, 한도가 순서대로 걸리는지가 핵심이다.
 * Redis 동작(SET NX, INCR)은 EmailVerificationRepository가 맡고 여기서는 그 결과에 따른 판단만 본다.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "dog@example.com";
    private static final String HASH = "h".repeat(64);
    private static final String IP = "203.0.113.10";

    @Mock
    UserRepository userRepository;
    @Mock
    WithdrawnEmailService withdrawnEmailService;
    @Mock
    EmailHasher emailHasher;
    @Mock
    EmailVerificationRepository verificationRepository;
    @Mock
    EmailSender emailSender;

    @InjectMocks
    EmailVerificationService service;

    // ── 발송 ─────────────────────────────────────────

    @Test
    void 코드를_저장하고_같은_코드를_보낸다() {
        발송_한도가_남아_있다();

        service.sendCode(EMAIL, IP);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(verificationRepository).saveCode(eq(HASH), saved.capture());
        assertThat(saved.getValue()).matches("\\d{6}");
        verify(emailSender).sendVerificationCode(EMAIL, saved.getValue());
    }

    @Test
    void 코드_유효시간은_개인정보_처리방침과_메일_본문에_적힌_5분이다() {
        // 바꾸려면 방침과 templates/mail/의 "5분간" 문구를 함께 바꿔야 한다(ResendEmailSenderTest).
        assertThat(EmailVerificationRepository.CODE_TTL).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void 가입된_주소와_차단된_주소는_같은_문장으로_막고_아무것도_세지_않는다() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);
        assertThatThrownBy(() -> service.sendCode(EMAIL, IP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(EmailVerificationService.UNAVAILABLE_EMAIL);

        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(withdrawnEmailService.blocked(EMAIL)).willReturn(true);
        assertThatThrownBy(() -> service.sendCode(EMAIL, IP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(EmailVerificationService.UNAVAILABLE_EMAIL);

        verifyNoInteractions(verificationRepository, emailSender);
    }

    @Test
    void IP_한도를_넘으면_주소_쿨다운을_쓰지_않고_429() {
        given(verificationRepository.countIpSend(IP)).willReturn(21L);

        assertThatThrownBy(() -> service.sendCode(EMAIL, IP))
                .isInstanceOf(EmailVerificationService.TooManyRequestsException.class);

        verify(verificationRepository, never()).tryStartCooldown(anyString());
        verifyNoInteractions(emailSender);
    }

    @Test
    void IP_한도_20회째까지는_보낸다() {
        given(verificationRepository.countIpSend(IP)).willReturn(20L);
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.tryStartCooldown(HASH)).willReturn(true);
        given(verificationRepository.countSend(HASH)).willReturn(1L);

        service.sendCode(EMAIL, IP);

        verify(emailSender).sendVerificationCode(eq(EMAIL), anyString());
    }

    @Test
    void 일분_안에_다시_요청하면_429() {
        given(verificationRepository.countIpSend(IP)).willReturn(1L);
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.tryStartCooldown(HASH)).willReturn(false);

        assertThatThrownBy(() -> service.sendCode(EMAIL, IP))
                .isInstanceOf(EmailVerificationService.TooManyRequestsException.class)
                .hasMessageContaining("1분");

        verify(verificationRepository, never()).countSend(anyString());
        verifyNoInteractions(emailSender);
    }

    @Test
    void 한_주소로_시간당_6번째_발송은_429() {
        given(verificationRepository.countIpSend(IP)).willReturn(1L);
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.tryStartCooldown(HASH)).willReturn(true);
        given(verificationRepository.countSend(HASH)).willReturn(6L);

        assertThatThrownBy(() -> service.sendCode(EMAIL, IP))
                .isInstanceOf(EmailVerificationService.TooManyRequestsException.class);

        verify(verificationRepository, never()).saveCode(anyString(), anyString());
        verifyNoInteractions(emailSender);
    }

    @Test
    void 발송이_실패하면_코드와_쿨다운을_거두고_503_예외를_그대로_올린다() {
        발송_한도가_남아_있다();
        willThrow(new EmailSender.MailUnavailableException("실패", null))
                .given(emailSender).sendVerificationCode(eq(EMAIL), anyString());

        assertThatThrownBy(() -> service.sendCode(EMAIL, IP))
                .isInstanceOf(EmailSender.MailUnavailableException.class);

        verify(verificationRepository).deleteCode(HASH);
        verify(verificationRepository).clearCooldown(HASH);
    }

    // ── 확인 ─────────────────────────────────────────

    @Test
    void 코드가_맞으면_코드를_지우고_토큰을_준다() {
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findCode(HASH)).willReturn(Optional.of("012345"));
        given(verificationRepository.countAttempt(HASH)).willReturn(1L);

        String token = service.confirm(EMAIL, "012345");

        assertThat(token).isNotBlank();
        verify(verificationRepository).deleteCode(HASH);
        verify(verificationRepository).saveToken(eq(token), eq(HASH), any(Instant.class));
    }

    @Test
    void 코드가_없거나_만료됐으면_400이고_시도로_세지_않는다() {
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findCode(HASH)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(EMAIL, "012345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");

        verify(verificationRepository, never()).countAttempt(anyString());
    }

    @Test
    void 틀리면_남은_횟수를_알려주고_코드는_살려_둔다() {
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findCode(HASH)).willReturn(Optional.of("012345"));
        given(verificationRepository.countAttempt(HASH)).willReturn(2L);

        assertThatThrownBy(() -> service.confirm(EMAIL, "999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("남은 시도 3회");

        verify(verificationRepository, never()).deleteCode(anyString());
        verify(verificationRepository, never()).saveToken(anyString(), anyString(), any());
    }

    @Test
    void 다섯_번째로_틀리면_코드를_무효화한다() {
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findCode(HASH)).willReturn(Optional.of("012345"));
        given(verificationRepository.countAttempt(HASH)).willReturn(5L);

        assertThatThrownBy(() -> service.confirm(EMAIL, "999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초과");

        verify(verificationRepository).deleteCode(HASH);
    }

    @Test
    void 한도를_넘긴_시도는_맞는_코드여도_비교하지_않고_무효화한다() {
        // 동시에 들어온 요청들이 같은 코드를 읽은 뒤 횟수를 올린 경우. 여섯 번째부터는 맞춰볼 기회가 없다.
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findCode(HASH)).willReturn(Optional.of("012345"));
        given(verificationRepository.countAttempt(HASH)).willReturn(6L);

        assertThatThrownBy(() -> service.confirm(EMAIL, "012345"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(verificationRepository).deleteCode(HASH);
        verify(verificationRepository, never()).saveToken(anyString(), anyString(), any());
    }

    // ── 가입 시 토큰 확인 ─────────────────────────────

    @Test
    void 토큰이_이_주소를_인증한_것이면_인증_시각을_돌려준다() {
        Instant verifiedAt = Instant.parse("2026-11-02T01:00:00Z");
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findToken("t"))
                .willReturn(Optional.of(new EmailVerificationRepository.Verified(HASH, verifiedAt)));

        assertThat(service.requireVerified("t", EMAIL)).isEqualTo(verifiedAt);
    }

    @Test
    void 다른_주소를_인증한_토큰이나_없는_토큰은_403() {
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.findToken("other"))
                .willReturn(Optional.of(new EmailVerificationRepository.Verified("x".repeat(64), Instant.now())));
        given(verificationRepository.findToken("missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireVerified("other", EMAIL))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.requireVerified("missing", EMAIL))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 코드는_언제나_여섯_자리_숫자다() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(EmailVerificationService.newCode()).matches("\\d{6}");
        }
    }

    private void 발송_한도가_남아_있다() {
        given(verificationRepository.countIpSend(IP)).willReturn(1L);
        given(emailHasher.hash(EMAIL)).willReturn(HASH);
        given(verificationRepository.tryStartCooldown(HASH)).willReturn(true);
        given(verificationRepository.countSend(HASH)).willReturn(1L);
    }
}
