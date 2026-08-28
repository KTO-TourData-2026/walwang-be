package com.server.walwang_be.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * application.yaml 의 jwt.* 설정.
 *
 * jwt:
 *   private-key: classpath:keys/private.pem   # 서명(발급)용
 *   public-key:  classpath:keys/public.pem    # 검증(읽기)용
 *   access-token-validity: 3600               # 초
 *   refresh-token-validity: 1209600           # 초
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        Resource privateKey,
        Resource publicKey,
        long accessTokenValidity,
        long refreshTokenValidity
) {
}
