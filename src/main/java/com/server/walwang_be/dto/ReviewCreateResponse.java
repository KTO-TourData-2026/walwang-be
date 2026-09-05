package com.server.walwang_be.dto;

import com.server.walwang_be.entity.StampStatus;

import java.util.UUID;

/**
 * 리뷰 등록 결과.
 *
 * stampUrl은 확정된 최종 주소다 — READY면 실루엣 도장, FALLBACK이면 기본 발도장.
 * 프론트가 이 값을 그대로 쓰면 되도록 등록 응답에서 도장 생성을 끝내고 내보낸다.
 *
 * 동반 불가로 남긴 리뷰는 도장을 찍지 않는다. 그때 stampStatus는 null이고,
 * stampUrl에는 화면이 비지 않도록 기본 발도장이 들어간다.
 */
public record ReviewCreateResponse(
        UUID reviewId,
        String stampUrl,
        StampStatus stampStatus,
        StoreStatusSummary store
) {
}
