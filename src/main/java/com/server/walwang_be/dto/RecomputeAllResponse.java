package com.server.walwang_be.dto;

import java.util.List;
import java.util.UUID;

/**
 * 일괄 재집계 결과.
 *
 * 실패한 가게를 함께 돌려준다 — 몇 건 실패했다고만 하면 어디를 다시 손봐야 할지 알 수 없다.
 */
public record RecomputeAllResponse(
        /** 리뷰가 있어 대상이 된 가게 수. */
        int total,
        int succeeded,
        int failed,
        long elapsedMs,
        List<Failure> failures
) {
    public record Failure(UUID storeId, String message) {
    }
}
