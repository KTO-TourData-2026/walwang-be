package com.server.walwang_be.dto;

import jakarta.validation.constraints.NotBlank;

/** 재발급 / 로그아웃 요청 본문. */
public record TokenRequest(
        @NotBlank String refreshToken
) {
}
