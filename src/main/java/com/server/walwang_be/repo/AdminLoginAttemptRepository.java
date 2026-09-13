package com.server.walwang_be.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 관리자 로그인 실패 횟수와 이미 쓴 OTP.
 *
 * 서버를 재시작해도 잠금이 풀리지 않도록 메모리가 아니라 Redis에 둔다.
 *
 * 아이디 기준과 IP 기준을 따로 센다.
 *   - 아이디 5회: 한 계정의 비밀번호를 맞히려는 시도를 막는다.
 *   - IP 20회: 한 곳에서 아이디를 바꿔 가며 찌르는 시도를 막는다.
 *     관리자 두 명이 같은 사무실 IP를 쓸 수 있어 아이디 기준보다 넉넉히 둔다.
 */
@Repository
@RequiredArgsConstructor
public class AdminLoginAttemptRepository {

    static final int MAX_FAILURES_PER_USER = 5;
    static final int MAX_FAILURES_PER_IP = 20;
    /** 첫 실패부터 이 시간 동안 센다. 잠기면 이 창이 끝날 때까지 잠겨 있다. */
    static final Duration WINDOW = Duration.ofMinutes(15);
    /**
     * 쓴 OTP를 기억하는 시간. 검증이 앞뒤 한 칸(±30초)을 받아주므로
     * 한 코드가 유효한 시간은 최대 90초다. 그보다 길게 잡는다.
     */
    private static final Duration USED_TOTP_TTL = Duration.ofMinutes(3);

    private static final String USER_KEY = "admin:login:fail:user:";
    private static final String IP_KEY = "admin:login:fail:ip:";
    private static final String TOTP_KEY = "admin:totp:used:";

    private final StringRedisTemplate redisTemplate;

    public boolean isLocked(String username, String ip) {
        return count(USER_KEY + username) >= MAX_FAILURES_PER_USER
                || count(IP_KEY + ip) >= MAX_FAILURES_PER_IP;
    }

    public void recordFailure(String username, String ip) {
        increment(USER_KEY + username);
        increment(IP_KEY + ip);
    }

    public void clearFailures(String username, String ip) {
        redisTemplate.delete(USER_KEY + username);
        redisTemplate.delete(IP_KEY + ip);
    }

    /**
     * 이 계정에서 이 시간 칸의 코드를 처음 쓰는지. 처음이면 표시하고 true.
     *
     * SET NX 한 번으로 확인과 표시를 같이 한다 — 따로 하면 같은 코드로 동시에 들어온
     * 두 요청이 둘 다 "처음"으로 보인다. 어깨너머로 본 코드를 곧바로 다시 쓰는 것을 막는다.
     */
    public boolean markTotpStepUsed(String username, long timeStep) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(TOTP_KEY + username + ":" + timeStep, "1", USED_TOTP_TTL));
    }

    private long count(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void increment(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        // 첫 실패에서만 만료를 건다. 매번 걸면 계속 찌르는 동안 창이 끝없이 밀린다.
        if (value != null && value == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
    }
}
