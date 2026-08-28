package com.server.walwang_be.dto;

import com.server.walwang_be.entity.TermCode;
import jakarta.validation.constraints.NotNull;

public record AgreementRequest(
        @NotNull TermCode termCode,
        @NotNull Boolean agreed
) {
}
