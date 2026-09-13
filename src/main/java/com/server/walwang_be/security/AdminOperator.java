package com.server.walwang_be.security;

import com.server.walwang_be.entity.AccessLog;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * 접속기록의 operator를 정하는 곳.
 *
 * 관리자 권한이 붙은 인증에서만 이름을 꺼낸다. 앱 JWT로 채워진 인증(principal이 UUID, ROLE_USER)이
 * 어떤 경로로든 관리자 화면까지 오면 여기서 막힌다 — 그 사람 이름으로 기록하느니 화면을 안 여는 편이 낫다.
 */
public final class AdminOperator {

    private AdminOperator() {
    }

    public static String require(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof String name)
                || authentication.getAuthorities().stream()
                .noneMatch(authority -> AdminAuthenticationProvider.ROLE.equals(authority.getAuthority()))) {
            throw new AccessDeniedException("관리자 인증이 아닙니다.");
        }
        return name;
    }

    /**
     * 입력된 아이디를 기록·키에 쓸 수 있게 다듬는다. 로그인 실패 행에는 아무 문자열이나 들어올 수 있다.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.strip().replaceAll("\\p{Cntrl}", "");
        return cleaned.length() <= AccessLog.OPERATOR_MAX ? cleaned : cleaned.substring(0, AccessLog.OPERATOR_MAX);
    }
}
