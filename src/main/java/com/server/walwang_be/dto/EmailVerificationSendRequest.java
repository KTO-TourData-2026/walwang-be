package com.server.walwang_be.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationSendRequest(
        @NotBlank @Email @Size(max = 320) String email
) {
}
