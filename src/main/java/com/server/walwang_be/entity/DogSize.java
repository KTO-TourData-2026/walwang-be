package com.server.walwang_be.entity;

/**
 * 반려견 크기. 가게의 동반 가능 여부가 이 단위로 갈리므로
 * 리뷰와 가게 상태 모두 같은 값을 쓴다.
 */
public enum DogSize {

    /** 소·중형견 */
    SMALL_MEDIUM("소·중형"),
    /** 대형견 */
    LARGE("대형");

    private final String label;

    DogSize(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
