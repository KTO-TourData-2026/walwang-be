package com.server.walwang_be.repo;

import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreSizeStatusId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StoreSizeStatusRepository
        extends JpaRepository<StoreSizeStatus, StoreSizeStatusId> {

    List<StoreSizeStatus> findByStore_StoreId(UUID storeId);

    /** 지도 핀 목록처럼 여러 가게의 상태가 한 번에 필요할 때 쓴다(N+1 방지). */
    List<StoreSizeStatus> findByStore_StoreIdIn(Collection<UUID> storeIds);
}
