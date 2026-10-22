package com.server.walwang_be.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SignUpRequest(
        @NotBlank @Size(max = 30) String nickname,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Email String email,
        @NotEmpty @Valid List<AgreementRequest> agreements,
        /** 이메일 인증 확인(/user/email-verification/confirm)이 내준 토큰. */
        @NotBlank @Size(max = 64) String verificationToken
) {
}
