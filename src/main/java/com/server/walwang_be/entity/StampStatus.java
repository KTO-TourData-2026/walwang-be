package com.server.walwang_be.entity;

/**
 * 발도장 생성 상태.
 *
 * 세그멘테이션은 오래 걸려서 리뷰 등록과 함께 처리하지 않는다.
 * 그래서 "아직 만드는 중"이라는 상태가 필요하다.
 */
public enum StampStatus {

    /** AI에 요청했거나 요청을 기다리는 중. 화면에는 기본 발도장을 보여준다. */
    PENDING,
    /** 실루엣 도장이 만들어져 stamp_url에 담겼다. */
    READY,
    /** 사진이 없거나 실루엣을 따지 못했다. 기본 발도장으로 확정. */
    FALLBACK
}
