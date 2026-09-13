package com.server.walwang_be.security;

import com.server.walwang_be.repo.AdminLoginAttemptRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 아이디 + 비밀번호 + 6자리 OTP.
 *
 * 빈으로 등록하지 않는다. AuthenticationProvider 빈이 생기면 스프링 부트가 전역
 * AuthenticationManager에 끼워 넣는데, 앱 API 체인까지 영향을 줄 이유가 없다.
 * AdminSecurityConfig가 만들어 관리자 체인에만 준다.
 *
 * 실패 이유가 무엇이든 화면에는 같은 문구가 나간다(AdminAuthenticationHandlers).
 * 이유는 예외 종류로만 구분해 접속기록의 action을 가른다.
 */
public class AdminAuthenticationProvider implements AuthenticationProvider {

    public static final String ROLE = "ROLE_ADMIN";
    public static final String CODE_PARAMETER = "code";

    private final AdminAccountRegistry accountRegistry;
    private final AdminLoginAttemptRepository loginAttempts;
    private final TotpVerifier totpVerifier;
    private final PasswordEncoder passwordEncoder;
    /**
     * 없는 아이디일 때 비교할 해시. 곧바로 실패하면 응답이 BCrypt 계산만큼 빨라져
     * 시간만 재도 아이디가 있는지 알 수 있다.
     */
    private final String dummyHash;

    public AdminAuthenticationProvider(AdminAccountRegistry accountRegistry,
                                       AdminLoginAttemptRepository loginAttempts,
                                       TotpVerifier totpVerifier,
                                       PasswordEncoder passwordEncoder) {
        this.accountRegistry = accountRegistry;
        this.loginAttempts = loginAttempts;
        this.totpVerifier = totpVerifier;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = AdminOperator.sanitize(authentication.getName());
        String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        LoginDetails details = authentication.getDetails() instanceof LoginDetails d
                ? d : new LoginDetails(ClientIpResolver.UNKNOWN, null);

        // 잠긴 동안에는 비밀번호를 확인하지도 않는다. 확인하면 잠긴 상태에서도 맞았는지 알아낼 방법이 생긴다.
        if (loginAttempts.isLocked(username, details.ip())) {
            throw new LockedException("잠긴 계정 또는 IP");
        }

        Optional<AdminAccountRegistry.AdminAccount> account = accountRegistry.find(username);
        String hash = account.map(AdminAccountRegistry.AdminAccount::bcrypt).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash);
        if (account.isEmpty() || !passwordMatches) {
            loginAttempts.recordFailure(username, details.ip());
            throw new BadCredentialsException("아이디 또는 비밀번호 불일치");
        }

        OptionalLong step = totpVerifier.verify(account.get().totpKey(), details.code());
        if (step.isEmpty()) {
            loginAttempts.recordFailure(username, details.ip());
            throw new TotpAuthenticationException("OTP 불일치");
        }
        if (!loginAttempts.markTotpStepUsed(username, step.getAsLong())) {
            loginAttempts.recordFailure(username, details.ip());
            throw new TotpAuthenticationException("이미 사용한 OTP");
        }

        loginAttempts.clearFailures(username, details.ip());
        return UsernamePasswordAuthenticationToken.authenticated(
                username, null, List.of(new SimpleGrantedAuthority(ROLE)));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /** 로그인 폼에서 비밀번호 외에 필요한 값. */
    public record LoginDetails(String ip, String code) {

        @Override
        public String toString() {
            return "LoginDetails[ip=" + ip + "]";
        }
    }

    public static AuthenticationDetailsSource<HttpServletRequest, LoginDetails> detailsSource(
            ClientIpResolver clientIpResolver) {
        return request -> new LoginDetails(
                clientIpResolver.resolve(request), request.getParameter(CODE_PARAMETER));
    }

    /** 비밀번호는 맞았고 OTP가 틀렸다. */
    public static class TotpAuthenticationException extends BadCredentialsException {
        public TotpAuthenticationException(String message) {
            super(message);
        }
    }
}
