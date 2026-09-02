package com.server.walwang_be.client;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 영수증에서 상호명을 읽어 등록된 가게명과 대조하는 AI 서비스.
 *
 * 응답의 verified / receipt_token은 쓰지 않는다 —
 * 통과 기준(임계값)과 토큰 발급은 서버가 쥐고 있어야 하기 때문이다.
 * 토큰에는 userId가 묶여야 하는데 AI 쪽은 누가 요청했는지 모른다.
 */
public interface ReceiptOcrClient {

    /**
     * @param storeName 서버가 store_id로 조회해 채워 보내는 등록 상호명
     * @throws OcrUnreadableException  글자를 못 읽었을 때 (422)
     * @throws OcrUnavailableException OCR 서버가 죽었거나 응답이 늦을 때 (503)
     */
    OcrResult verify(MultipartFile receipt, UUID storeId, String storeName);

    /**
     * @param matchedName OCR이 읽어낸 상호명
     * @param similarity  0.0 ~ 1.0
     */
    record OcrResult(String matchedName, double similarity) {
    }

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
