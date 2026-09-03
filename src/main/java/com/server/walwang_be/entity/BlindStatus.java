package com.server.walwang_be.entity;

/**
 * 리뷰 노출 상태. 정보통신망법 44조의2에 따른 임시조치를 담는다.
 *
 * 자동으로 바뀌지 않는다 — 신고가 쌓였다고 서버가 스스로 가리면
 * 경쟁 가게의 조직적 신고로 멀쩡한 리뷰가 사라진다. 관리자가 내용을 보고 직접 정한다.
 */
public enum BlindStatus {

    /** 정상 노출. */
    NORMAL,
    /** 임시조치. 판단이 끝날 때까지 가려둔다. 되돌릴 수 있다. */
    BLINDED,
    /** 삭제 확정. 행은 남기고 노출만 끊는다 — 처리 이력이 있어야 이의제기에 답할 수 있다. */
    DELETED;

    /** 화면과 집계에 들어가도 되는 상태인지. */
    public boolean visible() {
        return this == NORMAL;
    }
}
