package com.server.walwang_be.dto;

import java.util.UUID;

/** 리뷰 등록 결과. */
public record ReviewCreateResponse(
        UUID reviewId,
        String stampUrl,
        StoreStatusSummary store
) {
}
