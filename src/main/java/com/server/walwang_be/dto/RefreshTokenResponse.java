package com.server.walwang_be.dto;

/**
 * 로그인 / 재발급 응답 본문.
 * Access Token은 Authorization 헤더로 나가므로 본문에는 담지 않는다.
 */
public record RefreshTokenResponse(
        String refreshToken
) {
}
