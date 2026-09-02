package com.server.walwang_be.dto;

import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.StampStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 여권 목록의 도장 한 개.
 *
 * 아직 만들어지는 중이면 기본 발도장을 내보낸다 — 빈 칸을 보여주는 것보다
 * 도장이 찍혀 있다가 나중에 바뀌는 편이 자연스럽다.
 * status를 함께 주므로 화면에서 "생성 중" 표시를 붙일 수도 있다.
 */
public record PassportSummaryResponse(
        UUID id,
        String stampUrl,
        StampStatus status,
        Instant createdAt
) {
    public static PassportSummaryResponse from(Passport passport) {
        return new PassportSummaryResponse(
                passport.getPassportId(),
                passport.getStampUrl() == null
                        ? PawStampClient.DEFAULT_STAMP_URL
                        : passport.getStampUrl(),
                passport.getStatus(),
                passport.getCreatedAt());
    }
}
