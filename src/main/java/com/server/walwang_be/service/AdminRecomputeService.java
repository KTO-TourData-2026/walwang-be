package com.server.walwang_be.service;

import com.server.walwang_be.dto.AdminRecomputeStatus;
import com.server.walwang_be.dto.RecomputeAllResponse;
import com.server.walwang_be.dto.RecomputeResultResponse;
import com.server.walwang_be.entity.AccessAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 동반 가능 점수·태그 재집계(관리자 페이지). 예전 /internal API를 대신한다.
 *
 * <p><b>언제 쓰나.</b> 리뷰를 DB에서 직접 넣거나 고쳤을 때(목 리뷰 적재, 대시보드 SQL의 삭제 확정 등).
 * 등록·삭제·블라인드는 이미 스스로 재집계하므로 여기가 필요 없다.
 *
 * <p><b>축을 고르지 않는다.</b> 실제(is_demo=false)와 시연(is_demo=true)을 언제나 둘 다 센다.
 * store_size_status·store_tags는 is_demo로 행이 나뉘어 있어 한 축을 다시 세도 다른 축을 건드리지 않고,
 * 재집계는 저장된 리뷰를 다시 세는 것이라 몇 번 돌려도 결과가 같다. 고르게 두면 엉뚱한 축을
 * 고르고 "반영이 안 됐다"고 오해하는 일만 생긴다.
 *
 * <p><b>가게 하나</b>는 기록과 재집계를 같은 트랜잭션에 담는다(블라인드와 같은 규칙).
 * 기록을 재집계 뒤에 쓰는 이유는 집계에 쓰인 리뷰 수를 detail에 함께 남기려는 것이다 —
 * 같은 트랜잭션이라 순서와 상관없이 함께 커밋되거나 함께 없어진다. 없는 가게면 기록 전에 끝난다.
 *
 * <p><b>전체</b>는 백그라운드에서 돈다. 가게마다 트랜잭션이 따로라(BulkRecomputeService) 기록을
 * 함께 묶을 트랜잭션이 없다. 그래서 "실행 요청"을 먼저 커밋하고, 기록이 실패하면 시작하지 않는다 —
 * 기록 없이 도는 일은 없다. 결과(성공·실패 건수)는 이 화면과 서버 로그에 남는다.
 * 기록만 있고 결과가 실패인 경우가 생길 수 있지만, 재집계는 다시 돌리면 되는 작업이라 그대로 둔다.
 */
@Slf4j
@Service
public class AdminRecomputeService {

    /** 처리 사유 최대 길이. access_log.detail(500자)에 접두어와 함께 담긴다. */
    public static final int REASON_MAX = 200;

    /** 화면에 보여줄 실패 가게 id 수. 나머지는 서버 로그에 있다. */
    static final int FAILURES_SHOWN = 20;

    private static final String STORE_DETAIL = "가게 %s 재집계 — 집계 리뷰 실제 %d건·시연 %d건 — 사유: %s";
    private static final String ALL_DETAIL = "전체 재집계 실행(리뷰 또는 옛 값이 있는 가게, 실제·시연) — 사유: %s";

    private final AccessLogService accessLogService;
    private final ReviewService reviewService;
    private final BulkRecomputeService bulkRecomputeService;
    private final Executor adminJobExecutor;

    /** 전체 재집계가 도는 중인지. 서버 하나에서만 의미가 있다(운영은 EC2 한 대). */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant runningSince;
    private volatile LastRun lastRun;

    public AdminRecomputeService(AccessLogService accessLogService,
                                 ReviewService reviewService,
                                 BulkRecomputeService bulkRecomputeService,
                                 @Qualifier("adminJobExecutor") Executor adminJobExecutor) {
        this.accessLogService = accessLogService;
        this.reviewService = reviewService;
        this.bulkRecomputeService = bulkRecomputeService;
        this.adminJobExecutor = adminJobExecutor;
    }

    /** 가게 하나를 두 축 모두 다시 센다. */
    @Transactional
    public StoreResult recomputeStore(UUID storeId, String reason, String operator, String ip) {
        String trimmed = requireReason(reason);

        // ReviewService의 트랜잭션은 이 트랜잭션에 합류한다. 없는 가게면 여기서 NoSuchElementException.
        RecomputeResultResponse real = reviewService.recomputeStore(storeId, false);
        RecomputeResultResponse demo = reviewService.recomputeStore(storeId, true);

        accessLogService.recordInCurrentTransaction(
                operator, ip, AccessAction.RECOMPUTE_STORE, null,
                STORE_DETAIL.formatted(storeId, real.countedReviews(), demo.countedReviews(), trimmed));

        return new StoreResult(storeId, real.storeName(), real.countedReviews(), demo.countedReviews());
    }

    /**
     * 전체 재집계를 백그라운드로 시작한다. 이미 돌고 있으면 아무것도 하지 않고 거절한다.
     *
     * @return 시작 시각
     */
    public Instant startRecomputeAll(String reason, String operator, String ip) {
        String trimmed = requireReason(reason);
        if (!running.compareAndSet(false, true)) {
            throw new RecomputeRejected(
                    "전체 재집계가 이미 실행 중입니다. 끝난 뒤 다시 시도하세요. 아무것도 실행하지 않았습니다.");
        }
        Instant startedAt = Instant.now();
        try {
            // 기록이 먼저다. 실패하면 예외로 끝나 작업이 시작되지 않는다.
            accessLogService.record(operator, ip, AccessAction.RECOMPUTE_ALL, null, ALL_DETAIL.formatted(trimmed));
            runningSince = startedAt;
            adminJobExecutor.execute(() -> runAll(startedAt));
        } catch (RuntimeException e) {
            runningSince = null;
            running.set(false);
            throw e;
        }
        log.info("전체 재집계 시작: operator={}", operator);
        return startedAt;
    }

    private void runAll(Instant startedAt) {
        try {
            RecomputeAllResponse real = bulkRecomputeService.recomputeAll(false);
            RecomputeAllResponse demo = bulkRecomputeService.recomputeAll(true);
            lastRun = new LastRun(startedAt, Instant.now(), false, real, demo);
        } catch (RuntimeException e) {
            // 가게 단위 실패는 BulkRecomputeService가 삼키고 결과에 담는다. 여기 오는 건
            // 대상 조회처럼 작업 전체가 멈춘 경우다. 그때까지 커밋된 가게는 그대로 남는다.
            log.error("전체 재집계가 중간에 멈췄습니다", e);
            lastRun = new LastRun(startedAt, Instant.now(), true, null, null);
        } finally {
            runningSince = null;
            running.set(false);
        }
    }

    /** 화면용 상태. 조회만 하고 아무것도 바꾸지 않는다. */
    public AdminRecomputeStatus status() {
        Instant since = runningSince;
        LastRun last = lastRun;
        boolean isRunning = running.get();
        return new AdminRecomputeStatus(
                isRunning,
                // 시작 직후 잠깐 시각이 아직 안 채워졌을 수 있다. 빈 칸 대신 "방금"으로 둔다.
                since == null ? "방금" : AdminViewFormats.dateTime(since),
                last != null,
                last == null ? "" : AdminViewFormats.dateTime(last.startedAt()),
                last == null ? "" : AdminViewFormats.dateTime(last.finishedAt()),
                last == null ? 0 : Duration.between(last.startedAt(), last.finishedAt()).toSeconds(),
                last != null && last.aborted(),
                last == null ? null : axis("실제", last.real()),
                last == null ? null : axis("시연", last.demo()));
    }

    private static AdminRecomputeStatus.Axis axis(String label, RecomputeAllResponse result) {
        if (result == null) {
            return null;
        }
        var failures = result.failures();
        return new AdminRecomputeStatus.Axis(
                label, result.total(), result.succeeded(), result.failed(),
                !failures.isEmpty(),
                failures.stream().limit(FAILURES_SHOWN)
                        .map(failure -> failure.storeId().toString())
                        .toList(),
                failures.size() > FAILURES_SHOWN);
    }

    /** DB를 건드리기 전에 입력 모양만 본다. */
    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty()) {
            throw new RecomputeRejected("처리 사유를 입력하세요. 아무것도 실행하지 않았습니다.");
        }
        if (trimmed.length() > REASON_MAX) {
            throw new RecomputeRejected(
                    "처리 사유는 " + REASON_MAX + "자 이내로 입력하세요. 아무것도 실행하지 않았습니다.");
        }
        return trimmed;
    }

    /**
     * 가게 하나의 결과. 완료 메시지에 쓴다.
     *
     * 집계에 쓰인 리뷰 수를 축별로 보여준다 — 넣거나 가린 만큼 달라졌는지 그 자리에서 확인한다.
     */
    public record StoreResult(UUID storeId, String storeName, long realCounted, long demoCounted) {
    }

    private record LastRun(Instant startedAt, Instant finishedAt, boolean aborted,
                           RecomputeAllResponse real, RecomputeAllResponse demo) {
    }

    /** 실행하지 않고 되돌려보낸 요청. 메시지는 코드에 있는 고정 문자열만 담는다. */
    public static class RecomputeRejected extends RuntimeException {
        public RecomputeRejected(String message) {
            super(message);
        }
    }
}
