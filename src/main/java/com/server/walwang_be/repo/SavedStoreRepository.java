package com.server.walwang_be.repo;

import com.server.walwang_be.entity.SavedStore;
import com.server.walwang_be.entity.SavedStoreId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedStoreRepository extends JpaRepository<SavedStore, SavedStoreId> {

    boolean existsByUser_UserIdAndStore_StoreId(Long userId, Long storeId);

    /** 호출한 쪽에 트랜잭션이 필요하다. */
    long deleteByUser_UserIdAndStore_StoreId(Long userId, Long storeId);

    Page<SavedStore> findByUser_UserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
