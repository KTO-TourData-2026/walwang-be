package com.server.walwang_be.repo;

import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.entity.StoreTagCountId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StoreTagCountRepository
        extends JpaRepository<StoreTagCount, StoreTagCountId> {

    /**
     * 많이 선택된 태그부터. 상세 화면에 상위 몇 개만 보여줄 때 쓴다.
     *
     * store_size_status와 같은 이유로 축 없는 조회는 두지 않는다 —
     * 축을 빼면 시연 태그와 실제 태그가 한 목록에 섞여 나온다.
     */
    List<StoreTagCount> findByStore_StoreIdAndDemoOrderByCountDesc(UUID storeId, boolean demo);

    /** 코스처럼 여러 가게의 태그가 한 번에 필요할 때 쓴다(N+1 방지). */
    List<StoreTagCount> findByStore_StoreIdInAndDemo(Collection<UUID> storeIds, boolean demo);
}
