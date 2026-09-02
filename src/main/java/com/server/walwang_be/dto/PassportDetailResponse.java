package com.server.walwang_be.dto;

import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.StampStatus;

import java.time.Instant;
import java.util.UUID;

/** 여권 도장 상세. 어느 가게에서 찍은 도장인지와 그때 올린 사진이 함께 나간다. */
public record PassportDetailResponse(
        UUID id,
        UUID storeId,
        UUID reviewId,
        String stampUrl,
        StampStatus status,
        /** 리뷰에 사진이 없으면 비어 있다. */
        String photoUrl,
        String storeName,
        Instant createdAt
) {
    public static PassportDetailResponse from(Passport passport) {
        return new PassportDetailResponse(
                passport.getPassportId(),
                passport.getStore().getStoreId(),
                passport.getReview().getReviewId(),
                passport.getStampUrl() == null
                        ? PawStampClient.DEFAULT_STAMP_URL
                        : passport.getStampUrl(),
                passport.getStatus(),
                passport.getReview().getPhotoUrl(),
                passport.getStore().getName(),
                passport.getCreatedAt());
    }
}
