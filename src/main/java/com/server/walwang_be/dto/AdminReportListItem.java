package com.server.walwang_be.dto;

import java.util.UUID;

/**
 * 신고 목록 한 줄.
 *
 * 리뷰 본문은 담지 않는다 — 목록은 여러 사람의 리뷰가 한꺼번에 보이는데 target_user_id 가 비어 있어,
 * 누구의 무엇이 노출됐는지 기록되지 않는다. 본문은 대상이 기록되는 상세에서만 보인다
 * (회원 목록에서 이메일을 가리는 것과 같은 이유).
 *
 * @param blindedDays   임시조치 경과일. 가려져 있지 않으면 비어 있다
 * @param overdue       임시조치 30일을 넘겼는지. 화면에서 강조한다
 * @param reasonSummary 사유를 건수와 함께 요약한 문장
 */
public record AdminReportListItem(
        UUID reviewId,
        long reportCount,
        String lastReportedAt,
        String status,
        boolean blinded,
        String blindedDays,
        boolean overdue,
        String storeName,
        String nickname,
        String createdAt,
        String kind,
        String reasonSummary
) {
}
