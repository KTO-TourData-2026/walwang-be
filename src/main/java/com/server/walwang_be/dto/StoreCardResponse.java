package com.server.walwang_be.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.server.walwang_be.entity.Store;

import java.util.List;

/** 지도 핀 / 검색 결과 카드. 목록에 필요한 최소 정보만 담는다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StoreCardResponse(
        Long storeId,
        String storeName,
        GeoPoint geog,
        String type,
        List<SizeStatusResponse> sizeStatus
) {
    public static StoreCardResponse of(Store store, List<SizeStatusResponse> sizeStatus) {
        return new StoreCardResponse(
                store.getStoreId(),
                store.getName(),
                new GeoPoint(store.getLat(), store.getLng()),
                store.getType(),
                sizeStatus);
    }
}
