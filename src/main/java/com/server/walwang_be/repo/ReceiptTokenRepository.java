package com.server.walwang_be.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 영수증 인증 토큰. 인증에 통과한 (유저, 가게) 조합을 30분간 기억한다.
 *
 * 값에 userId까지 담는 이유는 토큰이 새어 나갔을 때를 대비하기 위해서다 —
 * 남의 토큰을 주워도 자기 계정으로는 쓸 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class ReceiptTokenRepository {

    private static final String KEY_PREFIX = "receipt:";
    /** 명세상 30분. 영수증을 찍고 리뷰를 쓰기에 충분하면서, 오래 재사용되지도 않는 길이다. */
    public static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public void save(String token, UUID userId, UUID storeId) {
        redisTemplate.opsForValue().set(key(token), userId + ":" + storeId, TTL);
    }

    /** 토큰이 가리키는 (userId, storeId). 없거나 만료됐으면 비어 있다. */
    public Optional<Issued> find(String token) {
        String value = redisTemplate.opsForValue().get(key(token));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(":", 2);
        return Optional.of(new Issued(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
    }

    /** 리뷰 등록에 쓴 토큰은 즉시 없앤다. 영수증 한 장에 리뷰 하나다. */
    public void delete(String token) {
        redisTemplate.delete(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }

    public record Issued(UUID userId, UUID storeId) {
    }
}
