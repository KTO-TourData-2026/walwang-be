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
 * 유실된 도장 작업을 다시 집는다.
 *
 * PassportService 안에 두면 @Async가 걸린 메서드를 같은 빈에서 부르게 되어
 * 프록시를 거치지 않는다 — 재시도가 스케줄러 스레드에서 동기로 돌아,
 * 한 건이 느리면 다음 주기까지 밀린다. 빈을 나눠 그 경로를 없앤다.
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
        // 다른 빈을 거치므로 @Async가 실제로 걸린다.
        stuck.forEach(passport -> passportService.generateStampAsync(passport.getPassportId()));
    }
}
