package com.server.walwang_be.repo;

import com.server.walwang_be.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /**
     * 응답에 작성자 닉네임이 들어가므로 user를 함께 가져온다 —
     * 안 그러면 리뷰 수만큼 추가 쿼리가 나간다(N+1).
     *
     * tags는 컬렉션이라 여기에 넣지 않는다. 컬렉션을 fetch join 하면
     * 페이징이 DB가 아닌 메모리에서 일어나 버린다.
     */
    @EntityGraph(attributePaths = "user")
    Page<Review> findByStore_StoreIdAndDemoOrderByCreatedAtDesc(
            UUID storeId, boolean demo, Pageable pageable);

    /**
     * 점수·태그 재집계용. 페이징이 없으니 태그까지 한 번에 끌어와도 안전하다.
     * 가게 하나에 달린 리뷰 전부를 읽으므로, 리뷰가 아주 많아지면
     * 집계 쿼리로 바꿔야 한다.
     */
    @EntityGraph(attributePaths = "tags")
    List<Review> findByStore_StoreIdAndDemo(UUID storeId, boolean demo);

    /** 회원 탈퇴 시 사진까지 지우려면 URL이 필요해 엔티티로 받는다. */
    List<Review> findByUser_UserId(UUID userId);

    /**
     * 내 리뷰 목록. 화면에 가게명·업종이 함께 나가므로 store를 같이 끌어온다.
     * tags는 컬렉션이라 넣지 않는다 — 페이징이 메모리에서 일어나 버린다.
     */
    @EntityGraph(attributePaths = "store")
    Page<Review> findByUser_UserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUser_UserId(UUID userId);
}
