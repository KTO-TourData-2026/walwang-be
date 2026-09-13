package com.server.walwang_be.dto;

import java.util.UUID;

/** 관리자 회원 목록 한 줄. 이메일은 가려서 담는다. */
public record AdminUserListItem(
        UUID userId,
        String maskedEmail,
        String nickname,
        String joinedAt,
        long reviewCount
) {
}
