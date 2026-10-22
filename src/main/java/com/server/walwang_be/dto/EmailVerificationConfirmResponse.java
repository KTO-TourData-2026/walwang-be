package com.server.walwang_be.dto;

/** 가입 요청(SignUpRequest.verificationToken)에 그대로 실어 보낸다. 30분 동안 유효하다. */
public record EmailVerificationConfirmResponse(String verificationToken) {
}
