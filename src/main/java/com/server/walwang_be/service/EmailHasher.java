package com.server.walwang_be.service;

import com.server.walwang_be.config.EmailHashProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 이메일을 되돌릴 수 없는 형태로 바꾼다.
 *
 * 페퍼가 없으면 아예 뜨지 않는다 — 조용히 페퍼 없이 해싱하면 보호가 걸려 있다고
 * 착각한 채로 배포된다. 없는 편이 낫다고 판단할 수는 있어도, 모르는 채로는 안 된다.
 */
@Component
@RequiredArgsConstructor
public class EmailHasher {

    private final EmailHashProperties properties;

    @PostConstruct
    void requirePepper() {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "EMAIL_PEPPER 환경변수가 설정되지 않았습니다. "
                            + "application.yaml에 security.email-pepper: ${EMAIL_PEPPER}를 두고 "
                            + "값은 환경변수로 주입하세요.");
        }
    }

    /**
     * SHA-256(페퍼 + 정규화한 이메일)을 16진수 64자로.
     *
     * 대소문자와 공백을 먼저 정리한다 — 가입할 때와 탈퇴할 때 표기가 다르면
     * 같은 주소인데도 다른 해시가 나와 차단이 그냥 새어 나간다.
     */
    public String hash(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(properties.emailPepper().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(
                    digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 표준 JDK가 반드시 제공한다. 여기 오면 실행 환경이 깨진 것이다.
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
