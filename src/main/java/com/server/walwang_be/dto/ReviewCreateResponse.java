package com.server.walwang_be.dto;

/** 리뷰 등록 결과. */
public record ReviewCreateResponse(
        Long reviewId,
        String stampUrl,
        StoreStatusSummary store
) {
}
