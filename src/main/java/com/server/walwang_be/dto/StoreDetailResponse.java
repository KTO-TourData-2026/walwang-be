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
        List<String> thumbnailUrls,
        /**
         * 한국관광공사 반려동물 동반 목록에 이름이 오른 곳인지. 상세 화면 배지에 쓴다.
         *
         * false는 두 가지를 함께 담는다 — 목록에 없거나, 공사 API 조회에 실패해
         * 확인하지 못한 경우다. 둘을 구분해야 하면 별도 필드가 필요하다.
         */
        boolean petVerified
) {
    /**
     * 가게 전체 건수는 크기별 행을 합쳐 만든다 — 같은 수를 stores에 따로 저장해 두면
     * 시연/실제 축까지 더해져 한 숫자를 네 곳에서 맞춰야 한다.
     * 크기별 행은 이미 이 응답에 실려 있으므로 더 읽어올 것도 없다.
     *
     * @param lastVerifiedAt 크기별 행 중 가장 최근 값. 합이 아니라 최댓값이라 따로 받는다
     */
    public static StoreDetailResponse of(Store store,
                                         List<String> tags,
                                         List<SizeStatusResponse> sizeStatus,
                                         Instant lastVerifiedAt,
                                         List<String> thumbnailUrls,
                                         boolean demo,
                                         boolean petVerified) {
        return new StoreDetailResponse(
                store.getStoreId(),
                store.displayName(demo),
                new GeoPoint(store.getLat(), store.getLng()),
                store.getAddress(),
                store.getType(),
                tags,
                store.getOpenTime(),
                store.getCloseTime(),
                sizeStatus.stream().mapToInt(SizeStatusResponse::possibleCount).sum(),
                sizeStatus.stream().mapToInt(SizeStatusResponse::impossibleCount).sum(),
                lastVerifiedAt,
                sizeStatus,
                thumbnailUrls,
                petVerified);
    }
}
