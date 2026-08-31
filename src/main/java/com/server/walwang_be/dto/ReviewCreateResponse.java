package com.server.walwang_be.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 리뷰 등록 결과. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReviewCreateResponse(
        Long reviewId,
        String stampUrl,
        StoreStatusSummary store
) {
}
