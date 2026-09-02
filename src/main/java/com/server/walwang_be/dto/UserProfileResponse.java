package com.server.walwang_be.dto;

import com.server.walwang_be.entity.User;

/**
 * 마이 화면에 쓰는 내 정보.
 *
 * 개수는 목록 API로 알 수 없어 따로 센다 — 리뷰도 여권도 페이징이라
 * 응답만 봐서는 전체가 몇 개인지 알 수 없다.
 */
public record UserProfileResponse(
        Long userId,
        String email,
        String nickname,
        int reviewCount,
        /** 모은 도장 수. */
        int stampCount,
        int savedStoreCount
) {
    public static UserProfileResponse of(User user, long reviewCount, long stampCount, long savedStoreCount) {
        return new UserProfileResponse(
                user.getUserId(),
                user.getEmail(),
                user.getNickname(),
                (int) reviewCount,
                (int) stampCount,
                (int) savedStoreCount);
    }
}
