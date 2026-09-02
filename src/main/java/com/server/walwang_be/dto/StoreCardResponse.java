package com.server.walwang_be.dto;

import com.server.walwang_be.entity.Store;

import java.util.List;
import java.util.UUID;

/**
 * 지도 핀 / 검색 결과 / 저장 장소 카드.
 *
 * 카드가 "업종 · 도로명주소"를 함께 보여주므로 address가 들어간다.
 * 좌표(geog)는 지도에 찍는 값이고 address는 사람이 읽는 값이라 둘 다 필요하다.
 *
 * storeName은 언제나 실제 상호다. 화면에 무엇을 띄울지는 displayName이 정한다 —
 * 둘을 나눠 두면 시연 중에도 어떤 가게인지 서버 로그·프론트 디버깅에서 알아볼 수 있다.
 */
public record StoreCardResponse(
        UUID storeId,
        String storeName,
        GeoPoint geog,
        String address,
        String type,
        List<SizeStatusResponse> sizeStatus,
        /** 화면에 보여줄 이름. 시연 모드에서 가명이 있을 때만 storeName과 달라진다. */
        String displayName
) {
    public static StoreCardResponse of(Store store, List<SizeStatusResponse> sizeStatus, boolean demo) {
        return new StoreCardResponse(
                store.getStoreId(),
                store.getName(),
                new GeoPoint(store.getLat(), store.getLng()),
                store.getAddress(),
                store.getType(),
                sizeStatus,
                store.displayName(demo));
    }
}
