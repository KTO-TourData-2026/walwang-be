package com.server.walwang_be.repo;

import com.server.walwang_be.entity.SavedStore;
import com.server.walwang_be.entity.SavedStoreId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SavedStoreRepository extends JpaRepository<SavedStore, SavedStoreId> {

    /** 중복 저장 판별. 시연 쪽과 실제 쪽은 따로 세므로 demo까지 봐야 한다. */
    boolean existsByUser_UserIdAndStore_StoreIdAndDemo(UUID userId, UUID storeId, boolean demo);

    /**
     * 저장 해제. demo까지 봐야 지울 행이 하나로 정해진다 —
     * PK가 (user_id, store_id, is_demo)라 같은 가게가 시연 쪽과 실제 쪽에
     * 각각 한 행씩 있을 수 있고, 축을 빼면 둘 다 지워진다.
     * 저장(existsBy...Demo)과 같은 축으로 맞춘 것이다.
     *
     * 호출한 쪽에 트랜잭션이 필요하다.
     */
    long deleteByUser_UserIdAndStore_StoreIdAndDemo(UUID userId, UUID storeId, boolean demo);

    /** 저장 목록. 카드에 가게 정보가 그대로 나가므로 store를 함께 끌어온다. */
    @EntityGraph(attributePaths = "store")
    Page<SavedStore> findByUser_UserIdAndDemoOrderByCreatedAtDesc(
            UUID userId, boolean demo, Pageable pageable);

    /** 마이 화면의 저장 장소 수. 목록과 같은 축으로 센다. */
    long countByUser_UserIdAndDemo(UUID userId, boolean demo);

    /** 회원 탈퇴 시 함께 정리한다. */
    void deleteByUser_UserId(UUID userId);
}
