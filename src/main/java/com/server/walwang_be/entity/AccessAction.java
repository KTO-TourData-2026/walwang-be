package com.server.walwang_be.entity;

/**
 * 접속기록의 수행업무.
 *
 * 값을 늘리거나 바꾸면 db/schema-access-log.sql 의 CHECK 제약도 함께 고친다 —
 * 안 고치면 새 값으로 기록하는 순간 INSERT가 실패하고, 기록이 실패하면 화면도 열리지 않는다.
 */
public enum AccessAction {
    LOGIN_SUCCESS("로그인"),
    /** 없는 아이디이거나 비밀번호가 틀렸다. 둘을 나누지 않는다 — 나누면 아이디 존재 여부가 드러난다. */
    LOGIN_FAILURE("로그인 실패(아이디·비밀번호)"),
    /** 비밀번호는 맞았고 OTP가 틀렸거나 이미 쓴 코드였다. 비밀번호 유출을 의심할 신호다. */
    TOTP_FAILURE("로그인 실패(OTP)"),
    /** 실패가 쌓여 잠긴 상태에서 다시 시도했다. */
    LOGIN_LOCKED("로그인 차단(잠금)"),
    LOGOUT("로그아웃"),
    LIST_USERS("회원 목록 조회"),
    SEARCH_USERS("회원 검색"),
    VIEW_USER("회원 상세 조회"),
    VIEW_ACCESS_LOG("접속기록 열람"),
    /**
     * 이용계약 해지(강제 탈퇴). 관리자 페이지의 유일한 쓰기 작업이다.
     *
     * 조회와 달리 기록이 삭제와 같은 트랜잭션에서 커밋된다 —
     * 판단 근거는 docs/admin-access-log.md "이용계약 해지" 참고.
     */
    TERMINATE_USER("이용계약 해지"),
    VIEW_REPORTS("신고 목록 조회"),
    /**
     * 신고된 리뷰 상세. 리뷰 본문과 사진이 화면에 나온다 —
     * 회원 상세(요약만)보다 많이 보여주는 자리라 대상 회원과 함께 남긴다.
     */
    VIEW_REPORTED_REVIEW("신고 리뷰 상세 조회"),
    /** 임시조치(약관 11조). 노출을 끊고 집계에서 뺀다. 되돌릴 수 있다. */
    BLIND_REVIEW("리뷰 블라인드"),
    UNBLIND_REVIEW("리뷰 블라인드 해제");

    private final String label;

    AccessAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
