package com.server.walwang_be.repo;

import com.server.walwang_be.entity.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, UUID> {

    /** 중복 신고 차단. unique 제약이 최후의 방어선이고, 이건 409를 제때 주기 위한 것이다. */
    boolean existsByReview_ReviewIdAndReporter_UserId(UUID reviewId, UUID reporterId);

    /** 회원 탈퇴 시 그 사람이 낸 신고를 지운다. */
    void deleteByReporter_UserId(UUID reporterId);

    /** 리뷰가 사라지면 그 리뷰를 향한 신고도 함께 지운다 — 남기면 FK에 걸린다. */
    void deleteByReview_ReviewId(UUID reviewId);

    /** 탈퇴로 리뷰 여러 건이 한꺼번에 사라질 때 쓴다. */
    void deleteByReview_ReviewIdIn(Collection<UUID> reviewIds);
}
