package com.server.walwang_be.client;

import java.time.Duration;

/**
 * 인증 메일 발송.
 *
 * 밖으로 나가는 것은 받는 주소와 인증 코드가 든 본문뿐이다(개인정보 처리방침).
 * 닉네임·태그·메타데이터 같은 것을 덧붙이지 않는다.
 */
public interface EmailSender {

    /**
     * @param validity 본문에 적을 유효시간. 실제 만료는 Redis TTL이 정한다.
     * @throws MailUnavailableException 발송 서버 장애·거절·타임아웃 (503)
     */
    void sendVerificationCode(String to, String code, Duration validity);

    /** 발송 실패. 사용자 잘못이 아니므로 500이 아니라 503으로 내려준다. */
    class MailUnavailableException extends RuntimeException {
        public MailUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
