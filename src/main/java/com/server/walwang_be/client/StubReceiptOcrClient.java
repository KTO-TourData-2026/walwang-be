package com.server.walwang_be.client;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * OCR 자리를 채워두는 임시 구현.
 *
 * 실제 OCR(CLOVA·Vision 등)을 붙일 때는 그 구현체에 @Primary를 달거나
 * 이 클래스를 지우면 된다. 빈이 둘 다 등록된 채로 두면 주입이 실패한다.
 *
 * 지금은 항상 읽기 실패로 응답한다 — 아무 영수증이나 통과시키면
 * 검증이 붙어 있다고 착각한 채 배포될 수 있어서 일부러 막아둔다.
 */
@Component
public class StubReceiptOcrClient implements ReceiptOcrClient {

    @Override
    public String readStoreName(MultipartFile receipt) {
        throw new OcrUnreadableException("OCR이 아직 연결되지 않았습니다.");
    }
}
