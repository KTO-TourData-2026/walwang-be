package com.server.walwang_be.security;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM 파일에서 RSA 키를 읽는다.
 * 개인키는 PKCS#8("BEGIN PRIVATE KEY"), 공개키는 X.509("BEGIN PUBLIC KEY") 형식이어야 한다.
 */
final class PemKeyLoader {

    private PemKeyLoader() {
    }

    static PrivateKey loadPrivateKey(Resource resource) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(read(resource))));
        } catch (Exception e) {
            throw new IllegalStateException("RSA 개인키를 읽지 못했습니다: " + resource, e);
        }
    }

    static PublicKey loadPublicKey(Resource resource) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decode(read(resource))));
        } catch (Exception e) {
            throw new IllegalStateException("RSA 공개키를 읽지 못했습니다: " + resource, e);
        }
    }

    private static String read(Resource resource) {
        if (resource == null) {
            throw new IllegalStateException("jwt 키 경로가 설정되지 않았습니다. application.yaml 의 jwt.private-key / jwt.public-key 를 확인하세요.");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("키 파일을 열 수 없습니다: " + resource, e);
        }
    }

    /** 헤더/푸터와 모든 공백(줄바꿈 포함)을 걷어내고 base64를 디코딩한다. */
    private static byte[] decode(String pem) {
        StringBuilder body = new StringBuilder(pem.length());
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-----")) {
                continue;
            }
            body.append(trimmed);
        }
        return Base64.getDecoder().decode(body.toString());
    }
}
