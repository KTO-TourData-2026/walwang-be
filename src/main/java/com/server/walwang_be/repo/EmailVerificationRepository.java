package com.server.walwang_be.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 가입 이메일 인증의 임시 상태.
 *
 *   email_verify:code:{h}      -> 6자리 코드            5분. 개인정보 처리방침에 적힌 유효시간이다.
 *   email_verify:attempts:{h}  -> 검증 시도 횟수        새 코드를 보내면 지운다.
 *   email_verify:cooldown:{h}  -> "1"                   1분. 같은 주소로 연달아 보내지 못하게 한다.
 *   email_verify:hourly:{h}    -> 발송 횟수            첫 발송부터 1시간.
 *   email_verify:ip:{ip}       -> 발송 요청 횟수        첫 요청부터 1시간.
 *   email_verify:token:{uuid}  -> {h}:{인증 시각 ms}    30분. 가입 요청이 들고 와서 소비한다.
 *
 * {h}는 EmailHasher의 해시다. 주소 원문을 Redis 키에 남기지 않는다.
 * IP는 원문 그대로 둔다 — AdminLoginAttemptRepository와 같고, 1시간이면 사라진다.
 */
@Repository
@RequiredArgsConstructor
public class EmailVerificationRepository {

    /** 인증 코드 유효시간. 개인정보 처리방침에 5분으로 적혀 있다 — 바꾸면 방침도 바꿔야 한다. */
    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration COOLDOWN = Duration.ofMinutes(1);
    static final Duration SEND_WINDOW = Duration.ofHours(1);
    /** 인증을 마치고 가입 양식을 채우기까지 기다리는 시간. */
    static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private static final String CODE_KEY = "email_verify:code:";
    private static final String ATTEMPTS_KEY = "email_verify:attempts:";
    private static final String COOLDOWN_KEY = "email_verify:cooldown:";
    private static final String HOURLY_KEY = "email_verify:hourly:";
    private static final String IP_KEY = "email_verify:ip:";
    private static final String TOKEN_KEY = "email_verify:token:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 쿨다운을 건다. 이미 걸려 있으면 false.
     * SET NX 한 번으로 확인과 표시를 같이 한다 — 따로 하면 동시에 온 두 요청이 둘 다 통과한다.
     */
    public boolean tryStartCooldown(String emailHash) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(COOLDOWN_KEY + emailHash, "1", COOLDOWN));
    }

    public void clearCooldown(String emailHash) {
        redisTemplate.delete(COOLDOWN_KEY + emailHash);
    }

    /** 이 주소로 보낸 횟수를 하나 올리고 올린 값을 돌려준다. */
    public long countSend(String emailHash) {
        return increment(HOURLY_KEY + emailHash, SEND_WINDOW);
    }

    /** 이 IP의 발송 요청 횟수를 하나 올리고 올린 값을 돌려준다. */
    public long countIpSend(String ip) {
        return increment(IP_KEY + ip, SEND_WINDOW);
    }

    /** 새 코드를 둔다. 이전 코드는 덮여 무효가 되고, 시도 횟수도 새로 센다. */
    public void saveCode(String emailHash, String code) {
        redisTemplate.opsForValue().set(CODE_KEY + emailHash, code, CODE_TTL);
        redisTemplate.delete(ATTEMPTS_KEY + emailHash);
    }

    public Optional<String> findCode(String emailHash) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(CODE_KEY + emailHash));
    }

    /** 검증 시도 횟수를 하나 올리고 올린 값을 돌려준다. 코드보다 오래 남지 않게 같은 TTL을 건다. */
    public long countAttempt(String emailHash) {
        return increment(ATTEMPTS_KEY + emailHash, CODE_TTL);
    }

    /** 코드를 무효화한다. 시도 횟수도 함께 지운다. */
    public void deleteCode(String emailHash) {
        redisTemplate.delete(CODE_KEY + emailHash);
        redisTemplate.delete(ATTEMPTS_KEY + emailHash);
    }

    public void saveToken(String token, String emailHash, Instant verifiedAt) {
        redisTemplate.opsForValue().set(
                TOKEN_KEY + token, emailHash + ":" + verifiedAt.toEpochMilli(), TOKEN_TTL);
    }

    /** 토큰이 가리키는 (이메일 해시, 인증 시각). 없거나 만료됐으면 비어 있다. */
    public Optional<Verified> findToken(String token) {
        String value = redisTemplate.opsForValue().get(TOKEN_KEY + token);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(":", 2);
        return Optional.of(new Verified(parts[0], Instant.ofEpochMilli(Long.parseLong(parts[1]))));
    }

    /** 가입에 쓴 토큰은 없앤다. 인증 한 번에 가입 하나다. */
    public void deleteToken(String token) {
        redisTemplate.delete(TOKEN_KEY + token);
    }

    private long increment(String key, Duration window) {
        Long value = redisTemplate.opsForValue().increment(key);
        // 처음 셀 때만 만료를 건다. 매번 걸면 계속 요청하는 동안 창이 끝없이 밀린다.
        if (value != null && value == 1L) {
            redisTemplate.expire(key, window);
        }
        return value == null ? 0 : value;
    }

    public record Verified(String emailHash, Instant verifiedAt) {
    }
}
