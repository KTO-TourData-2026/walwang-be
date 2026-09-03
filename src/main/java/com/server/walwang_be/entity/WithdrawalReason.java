package com.server.walwang_be.entity;

/**
 * 탈퇴 사유. 재가입을 막을지는 여기서 갈린다.
 */
public enum WithdrawalReason {

    /**
     * 자진 탈퇴.
     *
     * 기록만 하고 막지 않는다 — 약관 4조 4항이 승낙 거부 사유로 두는 것은
     * "약관 위반 해지 이력"뿐이라, 자진 탈퇴자까지 막으면 근거 없는 제약이 된다.
     */
    SELF,

    /** 약관 위반으로 인한 해지. 30일간 재가입을 막는다. */
    TERMINATED;

    /** 이 사유로 재가입을 막아도 되는지. */
    public boolean blocksSignUp() {
        return this == TERMINATED;
    }
}
