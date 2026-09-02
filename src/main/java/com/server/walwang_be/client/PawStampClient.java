package com.server.walwang_be.client;

/**
 * 반려견 사진에서 실루엣을 따 발도장 이미지를 만드는 AI 서비스.
 *
 * 응답이 두 갈래다 — 성공하면 PNG 바이너리가, 실루엣을 못 따면
 * {"result":"FALLBACK_PAW"} JSON이 온다. 그 구분을 여기서 흡수해
 * 호출하는 쪽은 "이미지가 나왔나 아닌가"만 보면 되게 한다.
 */
public interface PawStampClient {

    /** 기본 발도장. 사진이 없거나 실루엣을 못 땄을 때 이 값이 나간다. */
    String DEFAULT_STAMP_URL = "/static/stamp/default-paw.png";

    /**
     * @param photo 업로드된 반려견 사진(원본) 바이트
     * @return 만들어진 PNG 바이트. 실루엣을 못 땄으면 비어 있다
     * @throws StampUnavailableException AI 서비스 장애
     */
    java.util.Optional<byte[]> createStamp(byte[] photo);

    /** 세그멘테이션 서비스 장애. 나중에 다시 시도할 수 있다. */
    class StampUnavailableException extends RuntimeException {
        public StampUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
