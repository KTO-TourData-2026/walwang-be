package com.server.walwang_be.dto;

/**
 * 접속기록 열람 화면 한 줄.
 *
 * hasTarget을 따로 두는 이유: Mustache 섹션은 빈 문자열도 참으로 볼 수 있어
 * {{#targetUserId}} 로는 비어 있는지 가를 수 없다.
 */
public record AdminAccessLogRow(
        long logId,
        String accessedAt,
        String operator,
        String ip,
        boolean hasTarget,
        String targetUserId,
        String action,
        String actionLabel,
        String detail
) {
}
