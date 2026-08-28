package com.server.walwang_be.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 로그아웃된 Access Token 블랙리스트.
 * JWT는 그 자체로는 취소할 수 없어서, 남은 만료 시간만큼 Redis에 담아두고 거부한다.
 * TTL이 지나면 어차피 토큰도 만료되므로 자동으로 정리된다.
 *
 * 토큰 원문 대신 SHA-256 해시를 키로 쓴다(키 길이 절약 + 저장소에 토큰 원문을 남기지 않음).
 */
@Repository
@RequiredArgsConstructor
public class TokenBlacklistRepository {

    private static final String KEY_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String accessToken, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // 이미 만료된 토큰은 담아둘 필요가 없다.
        }
        redisTemplate.opsForValue().set(key(accessToken), "1", ttl);
    }

    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(accessToken)));
    }

    private String key(String accessToken) {
        return KEY_PREFIX + sha256(accessToken);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
