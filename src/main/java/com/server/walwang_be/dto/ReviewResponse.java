package com.server.walwang_be.dto;

import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.StoreTag;

import java.time.Instant;
import java.util.List;

/** 가게에 달린 리뷰 한 건. */
public record ReviewResponse(
        Long reviewId,
        String nickname,
        boolean dogAllowed,
        DogSize dogSize,
        String photoUrl,
        String thumbnailUrl,
        List<String> tags,
        String content,
        Instant createdAt
) {
    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getReviewId(),
                review.getUser().getNickname(),
                review.isDogAllowed(),
                review.getDogSize(),
                review.getPhotoUrl(),
                review.getThumbnailUrl(),
                review.getTags().stream().map(StoreTag::label).toList(),
                review.getContent(),
                review.getCreatedAt());
    }
}
