package com.server.walwang_be.entity;

/**
 * 약관 종류와 현재 버전.
 * 요청 본문에는 termCode만 오고, 버전은 서버가 이 값으로 기록한다.
 * 약관 내용이 바뀌면 여기 버전을 올리면 된다.
 */
public enum TermCode {

    /** 서비스 이용약관 */
    TERMS_OF_SERVICE("v1"),
    /** 개인정보 수집·이용 동의 */
    PRIVACY("v1"),
    /** 만 14세 이상 */
    AGE_14("v1");

    private final String version;

    TermCode(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }
}
