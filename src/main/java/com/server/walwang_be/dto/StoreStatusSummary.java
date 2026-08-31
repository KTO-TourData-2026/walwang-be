package com.server.walwang_be.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 리뷰 등록 직후 갱신된 가게 상태.
 * 방금 쓴 리뷰가 반영된 화면을 재조회 없이 바로 그릴 수 있게 돌려준다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StoreStatusSummary(
        Long storeId,
        List<SizeStatusResponse> sizeStatus
) {
}
