package com.server.walwang_be.dto;

/** 서비스 → 컨트롤러 내부 전달용. 응답 본문으로 그대로 나가지 않는다. */
public record TokenResponse(
        String accessToken,
        String refreshToken
) {
}
