package com.server.walwang_be.service;

import com.server.walwang_be.repo.AccessLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1년이 지난 접속기록을 지운다. 매일 04:30(KST).
 *
 * 법정 보관 기간은 "1년 이상"이다. 정보주체가 5만 명을 넘거나 고유식별정보·민감정보를 다루게 되면
 * 2년 이상이 되므로, 그때는 AccessLogRepository.deleteExpired 와
 * db/schema-access-log.sql 트리거의 interval 을 함께 늘린다.
 *
 * 탈퇴 이메일 정리(04:00)와 시각을 비켜 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogRetentionScheduler {

    private final AccessLogRepository accessLogRepository;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        int removed = accessLogRepository.deleteExpired();
        if (removed > 0) {
            log.info("보관 기간(1년)이 지난 접속기록 {}건을 정리했습니다.", removed);
        }
    }
}
