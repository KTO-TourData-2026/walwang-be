package com.server.walwang_be.dto;

import com.server.walwang_be.entity.Store;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 가게 상세. 리뷰 본문은 빠지고, 대신 리뷰에서 뽑은 썸네일과
 * 많이 선택된 태그가 함께 나간다.
 */
public record StoreDetailResponse(
        UUID storeId,
        String name,
        GeoPoint geog,
        String address,
        String type,
        List<String> tags,
        LocalTime openTime,
        LocalTime closeTime,
        int possibleCount,
        int impossibleCount,
        Instant lastVerifiedAt,
        List<SizeStatusResponse> sizeStatus,
        List<String> thumbnailUrls
) {
    public static StoreDetailResponse of(Store store,
                                         List<String> tags,
                                         List<SizeStatusResponse> sizeStatus,
                                         List<String> thumbnailUrls) {
        return new StoreDetailResponse(
                store.getStoreId(),
                store.getName(),
                new GeoPoint(store.getLat(), store.getLng()),
                store.getAddress(),
                store.getType(),
                tags,
                store.getOpenTime(),
                store.getCloseTime(),
                store.getPossibleCount(),
                store.getImpossibleCount(),
                store.getLastVerifiedAt(),
                sizeStatus,
                thumbnailUrls);
    }
}
