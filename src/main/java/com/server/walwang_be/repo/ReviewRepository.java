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
     * demo 축으로 거르지 않는다 — 시연용 목 리뷰도 실제 모드 화면에 함께 보인다.
     * 목 리뷰 본문에 시연용이라고 적어 두는 것으로 구분한다. 그래서 여기서 보이는 리뷰와
     * 점수·태그(축별로 따로 센다)가 어긋날 수 있다.
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
               and r.blindStatus = :blindStatus
               and (r.verified = true or r.user.userId = :viewerId)
             order by r.createdAt desc
            """,
            countQuery = """
            select count(r) from Review r
             where r.store.storeId = :storeId
               and r.blindStatus = :blindStatus
               and (r.verified = true or r.user.userId = :viewerId)
            """)
    Page<Review> findVisibleByStore(@Param("storeId") UUID storeId,
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
     *
     * demo 축으로도 거르지 않는다 — 가게 리뷰 목록(findVisibleByStore)과 같은 이유로,
     * 시연용 목 리뷰와 시연 모드에서 쓴 리뷰가 실제 모드 마이 화면에도 보인다.
     * 아래 카운트도 같은 축으로 세야 화면의 리뷰 수와 목록이 어긋나지 않는다.
     */
    @EntityGraph(attributePaths = "store")
    Page<Review> findByUser_UserIdAndBlindStatusOrderByCreatedAtDesc(
            UUID userId, BlindStatus blindStatus, Pageable pageable);

    /** 마이 화면의 리뷰 수. 목록과 같은 기준으로 센다(demo·verified 모두 거르지 않는다). */
    long countByUser_UserIdAndBlindStatus(UUID userId, BlindStatus blindStatus);

    /**
     * 한 축(demo)에서 다시 세면 값이 달라질 수 있는 가게의 id. 일괄 재집계 대상이다.
     *
     * 가게 7천 건을 전부 도는 대신 두 부류만 추린다.
     *   1. 그 축에 집계될 리뷰(인증·정상 노출)가 있는 가게 — 리뷰로부터 값을 다시 계산한다.
     *   2. 그 축의 store_size_status 나 store_tags 에 0이 아닌 값이 남은 가게 — 리뷰가 SQL로 지워졌거나
     *      모두 가려져 1번에서 빠졌어도, 옛 점수·태그가 남아 있으면 다시 세어 0으로 되돌려야 한다.
     * 둘 다 아닌 가게는 리뷰도 없고 값도 전부 0이라 다시 세도 결과가 같다.
     *
     * 2번이 한 번 0으로 돌아가면 다음 실행부터는 대상에서 빠진다 — 대상이 계속 불어나지 않는다.
     * last_verified_at 도 본다. 리뷰가 없으면 재집계가 null 로 되돌리는 값이라 남아 있으면 옛 흔적이다.
     *
     * 세 테이블에 걸쳐 UNION 이 필요해 네이티브 쿼리로 둔다. UNION 이 중복을 없앤다.
     * 'NORMAL' 은 BlindStatus.NORMAL 의 저장 값(EnumType.STRING)이다.
     */
    @Query(value = """
            select r.store_id from reviews r
             where r.is_demo = :demo
               and r.verified = true
               and r.blind_status = 'NORMAL'
            union
            select s.store_id from store_size_status s
             where s.is_demo = :demo
               and (s.possible_score <> 0 or s.impossible_score <> 0
                    or s.possible_count <> 0 or s.impossible_count <> 0
                    or s.last_verified_at is not null)
            union
            select t.store_id from store_tags t
             where t.is_demo = :demo
               and t.count <> 0
            order by 1
            """, nativeQuery = true)
    List<UUID> findRecomputeTargets(@Param("demo") boolean demo);
}
