package com.server.walwang_be.dto;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 회원 상세. 리뷰·저장·도장의 요약만 담는다.
 *
 * 리뷰 본문과 사진 URL은 담지 않는다. 요약 확인에 필요 없는 정보는 화면에 내지 않는다.
 */
public record AdminUserDetail(
        UUID userId,
        String email,
        String nickname,
        String joinedAt,
        long reviewTotal,
        List<CountRow> reviewStats,
        List<RecentReview> recentReviews,
        long savedStoreCount,
        long savedCourseCount,
        List<CourseRow> recentCourses,
        long stampTotal,
        List<CountRow> stampStats
) {
    public record CountRow(String label, long count) {
    }

    public record RecentReview(
            String createdAt,
            String storeName,
            String dogSize,
            String dogAllowed,
            String status,
            String kind
    ) {
    }

    public record CourseRow(String createdAt, String title, String kind) {
    }
}
