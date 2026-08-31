package com.server.walwang_be.client;

/**
 * 반려견 사진에서 실루엣을 따 발도장 이미지를 만든다.
 *
 * 세그멘테이션 모델이 아직 붙지 않아 인터페이스로 열어둔다.
 * 실패해도 리뷰 등록 자체는 성공해야 하므로, 구현체는 예외 대신 기본 이미지를 돌려준다.
 */
public interface PawStampClient {

    /** 기본 발도장. 사진이 없거나 실루엣을 못 땄을 때 이 값이 나간다. */
    String DEFAULT_STAMP_URL = "/static/stamp/default-paw.png";

    /**
     * @param photoUrl 업로드된 원본 사진 URL. 사진이 없으면 null
     * @return 발도장 이미지 URL. 실패 시 DEFAULT_STAMP_URL
     */
    String createStamp(String photoUrl);
}
