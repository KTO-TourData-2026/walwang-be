package com.server.walwang_be.repo;

import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 페이지의 신고 조회. AdminUserQueryRepository 와 같은 이유로 앱 레포지토리와 섞지 않는다.
 *
 * 쓰기 메서드는 없다 — 블라인드는 Review 엔티티가 자기 상태를 바꾸고 Hibernate 가 UPDATE 를 낸다.
 *
 * 신고자(reporter)는 어떤 쿼리에서도 꺼내지 않는다. 판단에 필요하지 않고,
 * 꺼내면 처리한 정보주체가 여러 명으로 늘어난다(docs/admin-access-log.md "리뷰 신고와 임시조치").
 */
public interface AdminModerationQueryRepository extends Repository<Review, UUID> {

    /** 신고 목록 한 줄. 가게 이름은 시연 여부에 따라 가명으로 바꿔야 하므로 둘 다 들고 온다. */
    interface ReportedReviewRow {
        UUID getReviewId();

        Long getReportCount();

        Instant getLastReportedAt();

        BlindStatus getBlindStatus();

        Instant getBlindedAt();

        Instant getCreatedAt();

        Boolean getDemo();

        String getNickname();

        String getStoreName();

        String getStoreDisplayName();
    }

    /** 리뷰 하나에 붙은 사유. 같은 사유가 여러 번이면 묶어서 센다. */
    interface ReasonCount {
        String getReason();

        Long getCount();

        Instant getLastReportedAt();
    }

    /**
     * 신고가 있는 리뷰를 건수 많은 순으로.
     *
     * 건수가 같으면 최근 신고가 위로 온다. 그마저 같으면 reviewId 로 순서를 고정한다 —
     * 페이지를 넘길 때 같은 행이 두 번 나오거나 빠지지 않게 한다.
     */
    @Query(value = """
            select r.reviewId as reviewId, count(rr) as reportCount,
                   max(rr.createdAt) as lastReportedAt,
                   r.blindStatus as blindStatus, r.blindedAt as blindedAt,
                   r.createdAt as createdAt, r.demo as demo,
                   u.nickname as nickname,
                   s.name as storeName, s.displayName as storeDisplayName
              from ReviewReport rr
              join rr.review r
              join r.user u
              join r.store s
             group by r.reviewId, r.blindStatus, r.blindedAt, r.createdAt, r.demo,
                      u.nickname, s.name, s.displayName
             order by count(rr) desc, max(rr.createdAt) desc, r.reviewId
            """,
            countQuery = "select count(distinct rr.review) from ReviewReport rr")
    Page<ReportedReviewRow> findReportedReviews(Pageable pageable);

    @Query("""
            select rr.reason as reason, count(rr) as count, max(rr.createdAt) as lastReportedAt
              from ReviewReport rr
             where rr.review.reviewId = :reviewId
             group by rr.reason
             order by count(rr) desc, max(rr.createdAt) desc
            """)
    List<ReasonCount> reasonsOf(@Param("reviewId") UUID reviewId);

    /** 상세 화면과 블라인드 처리가 함께 쓴다. 가게와 작성자를 같이 끌어온다. */
    @Query("""
            select r from Review r join fetch r.store join fetch r.user
             where r.reviewId = :reviewId
            """)
    Optional<Review> findForModeration(@Param("reviewId") UUID reviewId);

    /** 신고가 한 건이라도 있는지. 신고 없는 리뷰의 본문을 이 화면으로 열지 못하게 막는다. */
    @Query("select count(rr) from ReviewReport rr where rr.review.reviewId = :reviewId")
    long countReports(@Param("reviewId") UUID reviewId);

    /**
     * 임시조치 기간이 지난 리뷰. 상태를 바꾸지 않고 알리기만 한다(BlindExpiryScheduler).
     *
     * 가려진 리뷰는 소수라 blinded_at 에 인덱스를 두지 않는다. 전체 훑기로 충분하다.
     */
    @Query("""
            select r.reviewId from Review r
             where r.blindStatus = com.server.walwang_be.entity.BlindStatus.BLINDED
               and r.blindedAt < :threshold
             order by r.blindedAt
            """)
    List<UUID> findBlindedBefore(@Param("threshold") Instant threshold);
}
