package com.server.walwang_be.repo;

import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreSizeStatusId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StoreSizeStatusRepository
        extends JpaRepository<StoreSizeStatus, StoreSizeStatusId> {

    /**
     * demo를 빼고 읽는 메서드는 두지 않는다. 축을 빼면 (가게, 크기)당 두 행이 다 딸려 와
     * 시연 집계와 실제 집계가 섞이는데, 그게 눈에 띄는 건 화면에 잘못된 등급이 뜬 뒤다.
     * 컴파일 단계에서 막으려고 축이 없는 조회 자체를 없앴다.
     */
    List<StoreSizeStatus> findByStore_StoreIdAndDemo(UUID storeId, boolean demo);

    /** 지도 핀 목록처럼 여러 가게의 상태가 한 번에 필요할 때 쓴다(N+1 방지). */
    List<StoreSizeStatus> findByStore_StoreIdInAndDemo(Collection<UUID> storeIds, boolean demo);
}
