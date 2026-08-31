package com.server.walwang_be.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 영수증 인증 결과.
 *
 * 상호명이 안 맞는 건 실패(4xx)가 아니라 verified=false인 정상 응답이다 —
 * 사용자가 가게를 잘못 고른 흔한 상황이라, 화면에서 다시 고르게 안내하면 된다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReceiptVerifyResponse(
        boolean verified,
        /** 통과 시 null, 상호명 불일치 시 "NAME_MISMATCH" */
        String reason,
        String matchedName,
        double similarity,
        /** 통과 시에만 발급. 30분 뒤 만료된다. */
        String receiptToken
) {
    public static ReceiptVerifyResponse mismatch(String matchedName, double similarity) {
        return new ReceiptVerifyResponse(false, "NAME_MISMATCH", matchedName, similarity, null);
    }

    public static ReceiptVerifyResponse verified(String matchedName, double similarity, String token) {
        return new ReceiptVerifyResponse(true, null, matchedName, similarity, token);
    }
}
