package com.server.walwang_be.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 리뷰 신고 사유.
 *
 * 50자로 묶는다 — 신고는 관리자가 눈으로 훑고 판단하는 자리라
 * 길게 받아봐야 처리만 늦어진다. 자세한 사정은 고객센터로 간다.
 */
public record ReviewReportRequest(
        @NotBlank @Size(max = 50) String reason
) {
}
