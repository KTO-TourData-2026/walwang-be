package com.server.walwang_be.repo;

import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.entity.StoreTagCountId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreTagCountRepository
        extends JpaRepository<StoreTagCount, StoreTagCountId> {

    /** 많이 선택된 태그부터. 상세 화면에 상위 몇 개만 보여줄 때 쓴다. */
    List<StoreTagCount> findByStore_StoreIdOrderByCountDesc(Long storeId);
}
