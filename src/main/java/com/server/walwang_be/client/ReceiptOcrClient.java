package com.server.walwang_be.client;

import org.springframework.web.multipart.MultipartFile;

/**
 * 영수증에서 상호명을 읽어오는 OCR.
 *
 * 어떤 OCR을 붙일지는 아직 정해지지 않아 인터페이스로 열어둔다.
 * 구현체를 갈아끼우기만 하면 되도록 ReviewService는 이 타입에만 의존한다.
 */
public interface ReceiptOcrClient {

    /**
     * @return 읽어낸 상호명
     * @throws OcrUnreadableException  글자를 못 읽었을 때 (422)
     * @throws OcrUnavailableException OCR 서버가 죽었거나 응답이 늦을 때 (503)
     */
    String readStoreName(MultipartFile receipt);

    /** 이미지는 받았지만 상호명을 못 읽은 경우. 사용자가 다시 찍으면 해결된다. */
    class OcrUnreadableException extends RuntimeException {
        public OcrUnreadableException(String message) {
            super(message);
        }
    }

    /** OCR 쪽 장애. 사용자 잘못이 아니므로 메시지를 구분해서 내려준다. */
    class OcrUnavailableException extends RuntimeException {
        public OcrUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
