package com.server.walwang_be.service;

import com.server.walwang_be.dto.AgreementRequest;
import com.server.walwang_be.dto.SignUpRequest;
import com.server.walwang_be.entity.TermCode;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.UserAgreementRepository;
import com.server.walwang_be.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 가입은 이메일 인증 토큰이 있어야만 된다. 인증 시각이 users에 남고, 토큰은 한 번 쓰면 사라진다.
 *
 * 트랜잭션 동기화가 없는 목 환경이라 afterCommit은 그 자리에서 바로 돈다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceSignUpTest {

    private static final String EMAIL = "dog@example.com";
    private static final String TOKEN = "verification-token";

    @Mock
    UserRepository userRepository;
    @Mock
    UserAgreementRepository userAgreementRepository;
    @Mock
    WithdrawnEmailService withdrawnEmailService;
    @Mock
    EmailVerificationService emailVerificationService;
    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    UserService userService;

    @Test
    void 인증_시각을_users에_남기고_토큰을_소비한다() {
        Instant verifiedAt = Instant.parse("2026-11-02T01:00:00Z");
        given(emailVerificationService.requireVerified(TOKEN, EMAIL)).willReturn(verifiedAt);
        given(passwordEncoder.encode(anyString())).willReturn("hash");
        given(userRepository.save(any(User.class))).willAnswer(returnsFirstArg());

        userService.signUp(request());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmailVerifiedAt()).isEqualTo(verifiedAt);
        verify(emailVerificationService).consume(TOKEN);
    }

    @Test
    void 토큰이_없거나_다른_주소의_것이면_가입_여부를_묻기도_전에_막는다() {
        willThrow(new AccessDeniedException("x"))
                .given(emailVerificationService).requireVerified(TOKEN, EMAIL);

        assertThatThrownBy(() -> userService.signUp(request()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(userRepository, withdrawnEmailService, userAgreementRepository);
        verify(emailVerificationService, never()).consume(anyString());
    }

    @Test
    void 가입이_실패하면_토큰을_남겨_다시_인증하지_않고_재시도할_수_있다() {
        given(emailVerificationService.requireVerified(TOKEN, EMAIL)).willReturn(Instant.now());
        given(userRepository.existsByNickname("멍멍")).willReturn(true);

        assertThatThrownBy(() -> userService.signUp(request()))
                .isInstanceOf(IllegalStateException.class);

        verify(emailVerificationService, never()).consume(anyString());
    }

    private SignUpRequest request() {
        List<AgreementRequest> agreements = Arrays.stream(TermCode.values())
                .map(code -> new AgreementRequest(code, true))
                .toList();
        return new SignUpRequest("멍멍", "password123", EMAIL, agreements, TOKEN);
    }
}
