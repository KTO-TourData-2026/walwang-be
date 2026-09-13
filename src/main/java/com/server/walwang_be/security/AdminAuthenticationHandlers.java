package com.server.walwang_be.security;

import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.service.AccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import java.io.IOException;
import java.time.Duration;

/**
 * 로그인 성공·실패, 로그아웃을 접속기록에 남기고 다음 화면으로 보낸다.
 *
 * 여기서는 기록이 실패해도 흐름을 막지 않는다(서버 로그에 error로 남긴다).
 * 이 시점에는 개인정보가 화면에 나가지 않기 때문이다. 개인정보가 나가는 화면은
 * AdminController가 기록에 실패하면 열리지 않도록 따로 막는다.
 */
@Slf4j
public class AdminAuthenticationHandlers
        implements AuthenticationSuccessHandler, AuthenticationFailureHandler, LogoutSuccessHandler {

    /** 이 시간 동안 아무것도 안 하면 세션이 끊긴다(고시 제6조 자동 접속차단). */
    public static final Duration SESSION_IDLE = Duration.ofMinutes(30);

    private final AccessLogService accessLogService;
    private final ClientIpResolver clientIpResolver;

    public AdminAuthenticationHandlers(AccessLogService accessLogService, ClientIpResolver clientIpResolver) {
        this.accessLogService = accessLogService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        request.getSession().setMaxInactiveInterval((int) SESSION_IDLE.toSeconds());
        record(request, authentication.getName(), AccessAction.LOGIN_SUCCESS, "관리자 로그인");
        // 저장해 둔 원래 요청으로 돌려보내지 않는다. 늘 같은 첫 화면으로 간다.
        response.sendRedirect(request.getContextPath() + "/admin/users");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        AccessAction action;
        if (exception instanceof LockedException) {
            action = AccessAction.LOGIN_LOCKED;
        } else if (exception instanceof AdminAuthenticationProvider.TotpAuthenticationException) {
            action = AccessAction.TOTP_FAILURE;
        } else {
            action = AccessAction.LOGIN_FAILURE;
        }
        String attempted = AdminOperator.sanitize(request.getParameter("username"));
        record(request, attempted.isEmpty() ? "(빈 아이디)" : attempted, action,
                "관리자 로그인 실패: " + exception.getMessage());
        response.sendRedirect(request.getContextPath() + "/admin/login?error");
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException {
        // 세션이 이미 만료된 뒤 누른 로그아웃은 누구인지 알 수 없어 남기지 않는다.
        if (authentication != null) {
            record(request, authentication.getName(), AccessAction.LOGOUT, "관리자 로그아웃");
        }
        response.sendRedirect(request.getContextPath() + "/admin/login?logout");
    }

    private void record(HttpServletRequest request, String operator, AccessAction action, String detail) {
        try {
            accessLogService.record(operator, clientIpResolver.resolve(request), action, null, detail);
        } catch (RuntimeException e) {
            log.error("접속기록 저장 실패: operator={} action={}", operator, action, e);
        }
    }
}
