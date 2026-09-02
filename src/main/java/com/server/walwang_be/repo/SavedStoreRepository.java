package com.server.walwang_be.repo;

import com.server.walwang_be.entity.SavedStore;
import com.server.walwang_be.entity.SavedStoreId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedStoreRepository extends JpaRepository<SavedStore, SavedStoreId> {

    boolean existsByUser_UserIdAndStore_StoreId(Long userId, Long storeId);

    /** 호출한 쪽에 트랜잭션이 필요하다. */
    long deleteByUser_UserIdAndStore_StoreId(Long userId, Long storeId);

    /** 저장 목록. 카드에 가게 정보가 그대로 나가므로 store를 함께 끌어온다. */
    @EntityGraph(attributePaths = "store")
    Page<SavedStore> findByUser_UserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUser_UserId(Long userId);

    /** 회원 탈퇴 시 함께 정리한다. */
    void deleteByUser_UserId(Long userId);
}
