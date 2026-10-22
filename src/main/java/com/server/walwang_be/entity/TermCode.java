package com.server.walwang_be.entity;

/**
 * 약관 종류와 현재 버전.
 * 요청 본문에는 termCode만 오고, 버전은 서버가 이 값으로 기록한다.
 * 약관을 개정하면 이 값을 바꾼다. 날짜는 시행일 기준으로 README의 버전 표에 적는다.
 * 내용이 바뀌지 않은 약관은 값을 그대로 둔다 — 버전 문자열 하나가 문서 한 판본을 가리켜야
 * user_agreements.term_version으로 누가 어느 판본에 동의했는지 가릴 수 있다.
 * 값은 user_agreements.term_version(varchar 20)에 들어가므로 20자를 넘기지 않는다.
 */
public enum TermCode {

    /** 서비스 이용약관 */
    TERMS_OF_SERVICE("v2"),
    /** 개인정보 수집·이용 동의 */
    PRIVACY("v2"),
    /** 만 14세 이상. v2 개정분에서 내용 변경 없음 — v1 유지. */
    AGE_14("v1");

    private final String version;

    TermCode(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }
}
