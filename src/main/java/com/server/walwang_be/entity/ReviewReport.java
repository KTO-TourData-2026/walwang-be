package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 리뷰 신고. 약관 11조(정보통신망법 44조의2)가 정한 접수 창구다.
 *
 * (review_id, reporter_id)에 unique를 건다 — 한 사람이 같은 리뷰를 여러 번 눌러
 * 신고 수를 부풀리면 관리자가 심각도를 잘못 읽는다.
 *
 * 신고가 곧 블라인드는 아니다. 이 행은 관리자가 판단할 근거로만 쌓이고,
 * 노출 여부는 Review.blindStatus가 따로 정한다.
 */
@Entity
@Table(name = "review_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_report_review_reporter",
                columnNames = {"review_id", "reporter_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "report_id")
    private UUID reportId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Column(nullable = false, length = 50)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private ReviewReport(Review review, User reporter, String reason) {
        this.review = review;
        this.reporter = reporter;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
