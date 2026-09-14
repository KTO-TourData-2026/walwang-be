package com.server.walwang_be.dto;

import java.util.List;
import java.util.UUID;

/**
 * 신고된 리뷰 상세.
 *
 * 여기서만 리뷰 본문과 사진을 보여준다. 회원 상세(AdminUserDetail)는 요약만 내보내는데,
 * 신고 처리는 약관 11조가 요구하는 판단 자체이고 판단 대상이 본문과 사진이라 예외를 둔다.
 * 신고가 접수된 리뷰만 이 화면으로 열린다. 판단 근거는 docs/admin-access-log.md 참고.
 *
 * 신고자는 담지 않는다. 사유와 일시만으로 판단한다.
 *
 * @param photoUrl 원본 사진. 없으면 빈 문자열이다
 * @param reasons  사유별 건수. 건수 많은 순
 */
public record AdminReportedReviewDetail(
        UUID reviewId,
        UUID authorUserId,
        String nickname,
        String storeName,
        String createdAt,
        String content,
        boolean hasPhoto,
        String photoUrl,
        String dogSize,
        String dogAllowed,
        String kind,
        String status,
        boolean blinded,
        String blindedAt,
        String blindedDays,
        boolean overdue,
        long reportCount,
        List<ReasonRow> reasons
) {
    public record ReasonRow(String reason, long count, String lastReportedAt) {
    }
}
