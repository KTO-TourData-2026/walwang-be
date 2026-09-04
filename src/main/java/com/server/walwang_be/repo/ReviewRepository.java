package com.server.walwang_be.repo;

import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /**
     * 가게에 달린, 지금 보는 사람에게 보여도 되는 리뷰.
     *
     * 인증을 거치지 않은 리뷰(verified=false)는 작성자 본인에게만 보인다 —
     * 시연 모드에서 OCR 없이 쓴 리뷰라 가보지 않은 가게에도 달릴 수 있다.
     * viewerId가 null(비로그인)이면 비교가 참이 되지 않아 자연히 전부 빠진다.
     *
     * 응답에 작성자 닉네임이 들어가므로 user를 함께 가져온다 —
     * 안 그러면 리뷰 수만큼 추가 쿼리가 나간다(N+1).
     *
     * tags는 컬렉션이라 여기에 넣지 않는다. 컬렉션을 fetch join 하면
     * 페이징이 DB가 아닌 메모리에서 일어나 버린다.
     */
    @EntityGraph(attributePaths = "user")
    @Query(value = """
            select r from Review r
             where r.store.storeId = :storeId
               and r.demo = :demo
               and r.blindStatus = :blindStatus
               and (r.verified = true or r.user.userId = :viewerId)
             order by r.createdAt desc
            """,
            countQuery = """
            select count(r) from Review r
             where r.store.storeId = :storeId
               and r.demo = :demo
               and r.blindStatus = :blindStatus
               and (r.verified = true or r.user.userId = :viewerId)
            """)
    Page<Review> findVisibleByStore(@Param("storeId") UUID storeId,
                                    @Param("demo") boolean demo,
                                    @Param("blindStatus") BlindStatus blindStatus,
                                    @Param("viewerId") UUID viewerId,
                                    Pageable pageable);

    /**
     * 점수·태그 재집계용. 페이징이 없으니 태그까지 한 번에 끌어와도 안전하다.
     * 가게 하나에 달린 리뷰 전부를 읽으므로, 리뷰가 아주 많아지면
     * 집계 쿼리로 바꿔야 한다.
     *
     * 인증을 거치지 않은 리뷰는 세지 않는다 — 영수증 없이 쓸 수 있는 리뷰가
     * 점수를 움직이면 인증을 둔 의미가 없다.
     */
    @EntityGraph(attributePaths = "tags")
    List<Review> findByStore_StoreIdAndDemoAndVerifiedTrueAndBlindStatus(
            UUID storeId, boolean demo, BlindStatus blindStatus);

    /**
     * 회원 탈퇴 시 사진까지 지우려면 URL이 필요해 엔티티로 받는다.
     *
     * 여기만 blindStatus로 거르지 않는다 — 지우는 자리라 가려진 것도 함께 데려가야 한다.
     */
    List<Review> findByUser_UserId(UUID userId);

    /**
     * 내 리뷰 목록. 화면에 가게명·업종이 함께 나가므로 store를 같이 끌어온다.
     * tags는 컬렉션이라 넣지 않는다 — 페이징이 메모리에서 일어나 버린다.
     *
     * verified로는 거르지 않는다 — 인증을 건너뛴 리뷰도 내 것이면 보여야 한다.
     * 여권과 같은 demo 축으로 갈라야 마이 화면에서 리뷰 수와 도장 수가 어긋나지 않는다.
     */
    @EntityGraph(attributePaths = "store")
    Page<Review> findByUser_UserIdAndDemoAndBlindStatusOrderByCreatedAtDesc(
            UUID userId, boolean demo, BlindStatus blindStatus, Pageable pageable);

    /** 마이 화면의 리뷰 수. 목록과 같은 축으로 센다(verified는 여기서도 거르지 않는다). */
    long countByUser_UserIdAndDemoAndBlindStatus(
            UUID userId, boolean demo, BlindStatus blindStatus);

    /**
     * 리뷰가 하나라도 달린 가게의 id.
     *
     * 일괄 재집계 대상을 고르는 데 쓴다. 가게 7천 건을 전부 도는 대신
     * 리뷰가 있는 곳만 추린다 — 리뷰가 없으면 다시 세도 결과가 같다.
     *
     * 인증을 거치지 않은 리뷰는 어차피 집계에 들어가지 않으므로 대상에서도 뺀다.
     */
    @Query("""
            select distinct r.store.storeId from Review r
             where r.blindStatus = :blindStatus
               and r.verified = true
            """)
    List<UUID> findStoreIdsWithReviews(@Param("blindStatus") BlindStatus blindStatus);
}
