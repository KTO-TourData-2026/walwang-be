package com.server.walwang_be.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원 수정 요청.
 * nickname은 변경이 없더라도 기존 값을 그대로 보낸다.
 * 비밀번호를 바꿀 때만 pastPassword / newPassword를 함께 보낸다.
 */
public record UserPatchRequest(
        @NotBlank @Size(max = 30) String nickname,
        String pastPassword,
        @Size(min = 8, max = 64) String newPassword
) {

    /** 둘 다 있으면 비밀번호 변경, 둘 다 없으면 닉네임만 변경. 하나만 있으면 잘못된 요청. */
    public boolean hasPasswordChange() {
        return isPresent(pastPassword) && isPresent(newPassword);
    }

    public boolean hasPartialPasswordChange() {
        return isPresent(pastPassword) != isPresent(newPassword);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
