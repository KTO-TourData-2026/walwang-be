package com.server.walwang_be.dto;

import com.server.walwang_be.entity.Store;

import java.util.List;

/**
 * 지도 핀 / 검색 결과 / 저장 장소 카드.
 *
 * 카드가 "업종 · 도로명주소"를 함께 보여주므로 address가 들어간다.
 * 좌표(geog)는 지도에 찍는 값이고 address는 사람이 읽는 값이라 둘 다 필요하다.
 */
public record StoreCardResponse(
        Long storeId,
        String storeName,
        GeoPoint geog,
        String address,
        String type,
        List<SizeStatusResponse> sizeStatus
) {
    public static StoreCardResponse of(Store store, List<SizeStatusResponse> sizeStatus) {
        return new StoreCardResponse(
                store.getStoreId(),
                store.getName(),
                new GeoPoint(store.getLat(), store.getLng()),
                store.getAddress(),
                store.getType(),
                sizeStatus);
    }
}
