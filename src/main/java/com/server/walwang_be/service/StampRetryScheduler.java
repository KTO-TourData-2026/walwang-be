package com.server.walwang_be.service;

import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.repo.PassportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PENDING으로 남은 도장을 다시 집는다.
 *
 * 도장 생성이 리뷰 등록 트랜잭션 안으로 들어오면서 이 스케줄러의 역할이 바뀌었다.
 * 이제 새 리뷰는 PENDING으로 커밋되지 않는다 — 같은 트랜잭션 안에서 READY나
 * FALLBACK으로 확정되고, 중간에 서버가 죽으면 여권 행까지 통째로 롤백된다.
 *
 * 그래서 이건 비동기 시절에 PENDING으로 남은 기존 행을 치우는 뒷정리용이다.
 * 그런 행이 DB에서 사라지고 나면 지워도 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StampRetryScheduler {

    /** 이 시간이 지나도 PENDING이면 작업이 유실된 것으로 보고 다시 집는다. */
    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final PassportRepository passportRepository;
    private final PassportService passportService;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void retryStuckStamps() {
        List<Passport> stuck = passportRepository.findTop20ByStatusAndCreatedAtBefore(
                StampStatus.PENDING, Instant.now().minus(STUCK_AFTER));

        if (stuck.isEmpty()) {
            return;
        }
        log.info("발도장 재시도: PENDING {}건", stuck.size());
        // 스케줄러 스레드에서 순서대로 돈다. 한 건당 최대 8초(ai.stamp-timeout)라
        // 20건을 다 실패해도 160초, fixedDelay 5분 안에 끝난다.
        stuck.forEach(passport -> passportService.generateStampNow(passport.getPassportId()));
    }
}
