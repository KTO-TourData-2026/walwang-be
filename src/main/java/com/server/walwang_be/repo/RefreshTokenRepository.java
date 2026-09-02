package com.server.walwang_be.repo;

import com.server.walwang_be.security.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Refresh Token 저장소.
 *
 *   refresh:{token}       -> user_id            조회용. TTL로 자동 만료된다.
 *   user_refresh:{userId} -> Set{token, ...}    역인덱스. 유저의 토큰을 한 번에 폐기하기 위해 둔다.
 *
 * Refresh Token은 JWT가 아니라 불투명한 UUID다 —
 * Access Token과 같은 키로 서명하면 Refresh를 Access 자리에 넣어도 통과해버린다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String TOKEN_PREFIX = "refresh:";
    private static final String USER_INDEX_PREFIX = "user_refresh:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public void save(String refreshToken, UUID userId) {
        Duration ttl = Duration.ofSeconds(jwtProperties.refreshTokenValidity());

        redisTemplate.opsForValue().set(TOKEN_PREFIX + refreshToken, String.valueOf(userId), ttl);
        redisTemplate.opsForSet().add(userIndexKey(userId), refreshToken);
        // 역인덱스에도 TTL을 걸어 유저가 오래 접속하지 않으면 함께 사라지게 한다.
        redisTemplate.expire(userIndexKey(userId), ttl);
    }

    public Optional<UUID> findUserId(String refreshToken) {
        String userId = redisTemplate.opsForValue().get(TOKEN_PREFIX + refreshToken);
        return Optional.ofNullable(userId).map(UUID::fromString);
    }

    public void delete(String refreshToken) {
        findUserId(refreshToken).ifPresent(userId ->
                redisTemplate.opsForSet().remove(userIndexKey(userId), refreshToken));
        redisTemplate.delete(TOKEN_PREFIX + refreshToken);
    }

    /** 해당 유저의 모든 기기 로그아웃. 비밀번호 변경 시 사용한다. */
    public void deleteAllByUserId(UUID userId) {
        Set<String> tokens = redisTemplate.opsForSet().members(userIndexKey(userId));
        if (tokens != null && !tokens.isEmpty()) {
            redisTemplate.delete(tokens.stream().map(TOKEN_PREFIX::concat).toList());
        }
        redisTemplate.delete(userIndexKey(userId));
    }

    private String userIndexKey(UUID userId) {
        return USER_INDEX_PREFIX + userId;
    }
}
