package com.server.walwang_be.dto;

import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.StoreTag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 가게에 달린 리뷰 한 건. */
public record ReviewResponse(
        UUID reviewId,
        String nickname,
        boolean dogAllowed,
        DogSize dogSize,
        String photoUrl,
        String thumbnailUrl,
        List<String> tags,
        String content,
        Instant createdAt,
        /**
         * 지금 보고 있는 사람이 쓴 리뷰인지. 화면에서 신고 버튼을 감추는 데 쓴다.
         *
         * 서버도 본인 리뷰 신고를 400으로 막지만, 누를 수 있는 버튼을 보여주고
         * 누르면 거절하는 것보다 애초에 안 보여주는 편이 낫다.
         */
        boolean mine
) {
    /**
     * @param viewerId 조회한 사람. 비로그인이면 null이고 mine은 전부 false가 된다
     */
    public static ReviewResponse from(Review review, UUID viewerId) {
        return new ReviewResponse(
                review.getReviewId(),
                review.getUser().getNickname(),
                review.isDogAllowed(),
                review.getDogSize(),
                review.getPhotoUrl(),
                review.getThumbnailUrl(),
                review.getTags().stream().map(StoreTag::label).toList(),
                review.getContent(),
                review.getCreatedAt(),
                viewerId != null && review.getUser().getUserId().equals(viewerId));
    }
}
