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
 * storeName·displayName 모두 demo가 정한 이름이다. 시연 중에는 실명이 응답에
 * 아예 실리지 않는다 — 프론트가 어느 필드를 읽든 가명이 나가야 하기 때문이다.
 * (실명을 함께 내려 디버깅에 쓰던 방식은 노출 위험이 커서 그만뒀다.)
 *
 * address도 demo가 정한다. 필드 이름은 그대로 두고 값만 갈린다 —
 * 시연 중에는 상세 번지가 실리지 않는다.
 */
public record StoreCardResponse(
        UUID storeId,
        String storeName,
        GeoPoint geog,
        String address,
        String type,
        List<SizeStatusResponse> sizeStatus,
        /** 화면에 보여줄 이름. storeName과 항상 같은 값이며, 기존 클라이언트를 위해 남겨 둔다. */
        String displayName
) {
    public static StoreCardResponse of(Store store, List<SizeStatusResponse> sizeStatus, boolean demo) {
        String name = store.displayName(demo);
        return new StoreCardResponse(
                store.getStoreId(),
                name,
                new GeoPoint(store.getLat(), store.getLng()),
                store.displayAddress(demo),
                store.getType(),
                sizeStatus,
                name);
    }
}
