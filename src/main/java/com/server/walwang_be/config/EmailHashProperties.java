package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 security.* 설정.
 *
 * security:
 *   email-pepper: ${EMAIL_PEPPER}
 *
 * 페퍼 값은 파일에 쓰지 않는다. 환경변수로만 주입한다 —
 * 값이 새면 흔한 이메일 주소를 미리 계산해 해시를 맞춰볼 수 있게 된다.
 */
@ConfigurationProperties(prefix = "security")
public record EmailHashProperties(String emailPepper) {

    public boolean configured() {
        return emailPepper != null && !emailPepper.isBlank();
    }
}
