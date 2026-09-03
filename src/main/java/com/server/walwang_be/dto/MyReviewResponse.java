package com.server.walwang_be.dto;

import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.Store;

import java.time.Instant;
import java.util.UUID;

/**
 * 내가 쓴 리뷰 한 건.
 *
 * 가게별 리뷰 목록(ReviewResponse)과 달리 작성자 닉네임 대신 가게 정보가 들어간다 —
 * 내 리뷰 목록에서는 "누가 썼는지"가 아니라 "어디에 썼는지"가 필요하다.
 */
public record MyReviewResponse(
        UUID reviewId,
        UUID storeId,
        String storeName,
        /** 업종. */
        String type,
        boolean dogAllowed,
        DogSize dogSize,
        String photoUrl,
        String thumbnailUrl,
        Instant createdAt
) {
    /** @param demo 시연 모드면 가게 가명을 담는다. */
    public static MyReviewResponse from(Review review, boolean demo) {
        Store store = review.getStore();
        return new MyReviewResponse(
                review.getReviewId(),
                store.getStoreId(),
                store.displayName(demo),
                store.getType(),
                review.isDogAllowed(),
                review.getDogSize(),
                review.getPhotoUrl(),
                review.getThumbnailUrl(),
                review.getCreatedAt());
    }
}
