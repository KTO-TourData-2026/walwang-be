package com.server.walwang_be.service;

import com.server.walwang_be.repo.AdminModerationQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 임시조치 기간이 지난 리뷰를 알린다. 매일 05:00(KST).
 *
 * <b>상태는 바꾸지 않는다.</b> 정보통신망법 44조의2의 임시조치는 최장 30일이고,
 * 그 뒤에 삭제로 확정할지 되돌릴지는 사업자가 내용을 보고 정할 일이다.
 * 자동으로 DELETED 로 넘기면 이의제기 처리가 늦어진 건까지 서버가 삭제를 확정해 버리고,
 * 그건 신고 수만으로 자동 블라인드하지 않기로 한 판단(BlindStatus 주석)과 어긋난다.
 *
 * 그래서 여기서는 세어서 로그로 알리기만 한다. 관리자 화면의 신고 목록에도 경과일이 강조돼 나오고,
 * 처리 절차는 docs/admin-runbook.md "30일이 지난 임시조치" 에 있다.
 *
 * 탈퇴 이메일 정리(04:00), 접속기록 정리(04:30)와 시각을 비켜 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlindExpiryScheduler {

    /** 한 번에 로그에 남길 리뷰 id 개수. 많으면 앞의 것만 적고 건수로 알린다. */
    private static final int SAMPLE = 20;

    private final AdminModerationQueryRepository moderationQueryRepository;

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void warnExpired() {
        Instant threshold = Instant.now().minus(AdminModerationService.TEMPORARY_MEASURE);
        List<UUID> expired = moderationQueryRepository.findBlindedBefore(threshold);
        if (expired.isEmpty()) {
            return;
        }
        log.warn("임시조치 {}일이 지난 블라인드 리뷰가 {}건 있습니다. 삭제 확정 또는 복원을 판단하세요"
                        + " (관리자 페이지 > 신고). 오래된 순 {}건: {}",
                AdminModerationService.TEMPORARY_MEASURE.toDays(),
                expired.size(),
                Math.min(expired.size(), SAMPLE),
                expired.stream().limit(SAMPLE).toList());
    }
}
