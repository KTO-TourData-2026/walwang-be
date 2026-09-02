package com.server.walwang_be.dto;

import java.util.List;

/**
 * 리뷰 등록 직후 갱신된 가게 상태.
 * 방금 쓴 리뷰가 반영된 화면을 재조회 없이 바로 그릴 수 있게 돌려준다.
 */
public record StoreStatusSummary(
        Long storeId,
        List<SizeStatusResponse> sizeStatus
) {
}
