package com.server.walwang_be.service;

import com.server.walwang_be.dto.AdminRecomputeStatus;
import com.server.walwang_be.dto.RecomputeAllResponse;
import com.server.walwang_be.dto.RecomputeResultResponse;
import com.server.walwang_be.entity.AccessAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 관리자 재집계. 두 축을 모두 세는지, 기록이 어떤 방식으로 남는지, 실행하지 않는 경우를 고정한다.
 *
 * 전체 재집계의 실행기는 테스트가 직접 쥔다 — 즉시 실행하거나, 붙잡아 뒀다가 나중에 돌린다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRecomputeServiceTest {

    private static final UUID STORE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String OPERATOR = "alice";
    private static final String IP = "203.0.113.10";

    @Mock
    AccessLogService accessLogService;
    @Mock
    ReviewService reviewService;
    @Mock
    BulkRecomputeService bulkRecomputeService;

    /** 넘겨받은 작업을 바로 돌리지 않고 모아 둔다. runPending()으로 돌린다. */
    private final List<Runnable> pending = new ArrayList<>();
    private final Executor heldExecutor = pending::add;

    private AdminRecomputeService service;

    @BeforeEach
    void setUp() {
        service = new AdminRecomputeService(accessLogService, reviewService, bulkRecomputeService, heldExecutor);
        given(reviewService.recomputeStore(STORE_ID, false))
                .willReturn(new RecomputeResultResponse(STORE_ID, "테스트 카페", 3, List.of()));
        given(reviewService.recomputeStore(STORE_ID, true))
                .willReturn(new RecomputeResultResponse(STORE_ID, "시연 카페", 5, List.of()));
        given(bulkRecomputeService.recomputeAll(false)).willReturn(new RecomputeAllResponse(10, 10, 0, 5, List.of()));
        given(bulkRecomputeService.recomputeAll(true)).willReturn(new RecomputeAllResponse(7, 7, 0, 5, List.of()));
    }

    private void runPending() {
        List<Runnable> tasks = new ArrayList<>(pending);
        pending.clear();
        tasks.forEach(Runnable::run);
    }

    // ── 가게 하나 ──────────────────────────────────────────────

    @Test
    void 가게_재집계는_두_축을_모두_세고_같은_트랜잭션에_기록한다() {
        AdminRecomputeService.StoreResult result =
                service.recomputeStore(STORE_ID, "  목 리뷰 SQL 적재  ", OPERATOR, IP);

        // 실제 축의 이름(가명이 아닌 상호)을 돌려준다.
        assertThat(result.storeName()).isEqualTo("테스트 카페");
        assertThat(result.realCounted()).isEqualTo(3);
        assertThat(result.demoCounted()).isEqualTo(5);

        InOrder order = inOrder(reviewService, accessLogService);
        order.verify(reviewService).recomputeStore(STORE_ID, false);
        order.verify(reviewService).recomputeStore(STORE_ID, true);
        order.verify(accessLogService).recordInCurrentTransaction(
                OPERATOR, IP, AccessAction.RECOMPUTE_STORE, null,
                "가게 " + STORE_ID + " 재집계 — 집계 리뷰 실제 3건·시연 5건 — 사유: 목 리뷰 SQL 적재");
    }

    /** 조회 화면이 쓰는 record(REQUIRES_NEW)를 부르면 기록이 따로 커밋돼 원자성이 깨진다. */
    @Test
    void 가게_재집계는_따로_커밋되는_기록_메서드를_쓰지_않는다() {
        service.recomputeStore(STORE_ID, "목 리뷰 SQL 적재", OPERATOR, IP);

        verify(accessLogService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 없는_가게면_기록하지_않고_끝난다() {
        UUID missing = UUID.randomUUID();
        willThrow(new NoSuchElementException("존재하지 않는 가게입니다."))
                .given(reviewService).recomputeStore(missing, false);

        assertThatThrownBy(() -> service.recomputeStore(missing, "목 리뷰 SQL 적재", OPERATOR, IP))
                .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(accessLogService);
    }

    @Test
    void 사유가_비었거나_길면_아무것도_하지_않는다() {
        String tooLong = "가".repeat(AdminRecomputeService.REASON_MAX + 1);

        for (String reason : new String[]{null, "", "   ", tooLong}) {
            assertThatThrownBy(() -> service.recomputeStore(STORE_ID, reason, OPERATOR, IP))
                    .isInstanceOf(AdminRecomputeService.RecomputeRejected.class);
            assertThatThrownBy(() -> service.startRecomputeAll(reason, OPERATOR, IP))
                    .isInstanceOf(AdminRecomputeService.RecomputeRejected.class);
        }

        verifyNoInteractions(reviewService, bulkRecomputeService, accessLogService);
        assertThat(pending).isEmpty();
    }

    // ── 전체 ────────────────────────────────────────────────

    @Test
    void 전체_재집계는_실행_요청을_먼저_기록하고_백그라운드에서_두_축을_센다() {
        service.startRecomputeAll("목 리뷰 적재", OPERATOR, IP);

        // 기록은 시작 전에 따로 커밋된다. 가게마다 트랜잭션이 달라 묶을 트랜잭션이 없다.
        verify(accessLogService).record(OPERATOR, IP, AccessAction.RECOMPUTE_ALL, null,
                "전체 재집계 실행(리뷰 또는 옛 값이 있는 가게, 실제·시연) — 사유: 목 리뷰 적재");
        verify(accessLogService, never()).recordInCurrentTransaction(any(), any(), any(), any(), any());
        // 요청 스레드에서는 아직 돌지 않았다.
        verifyNoInteractions(bulkRecomputeService);
        assertThat(service.status().running()).isTrue();

        runPending();

        InOrder order = inOrder(bulkRecomputeService);
        order.verify(bulkRecomputeService).recomputeAll(false);
        order.verify(bulkRecomputeService).recomputeAll(true);

        AdminRecomputeStatus status = service.status();
        assertThat(status.running()).isFalse();
        assertThat(status.hasLastRun()).isTrue();
        assertThat(status.lastAborted()).isFalse();
        assertThat(status.real().total()).isEqualTo(10);
        assertThat(status.demo().total()).isEqualTo(7);
    }

    @Test
    void 도는_중에는_두_번째_요청을_거절하고_기록도_남기지_않는다() {
        service.startRecomputeAll("첫 번째", OPERATOR, IP);

        assertThatThrownBy(() -> service.startRecomputeAll("두 번째", "bob", IP))
                .isInstanceOf(AdminRecomputeService.RecomputeRejected.class)
                .hasMessageContaining("이미 실행 중");

        verify(accessLogService, never()).record(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("두 번째"));
        assertThat(pending).hasSize(1);

        // 끝나면 다시 받을 수 있다.
        runPending();
        service.startRecomputeAll("세 번째", OPERATOR, IP);
        assertThat(pending).hasSize(1);
    }

    /** 기록 없이 도는 일은 없어야 한다. */
    @Test
    void 기록이_실패하면_시작하지_않고_다시_받을_수_있는_상태로_돌아간다() {
        willThrow(new IllegalStateException("CHECK 제약 위반"))
                .given(accessLogService).record(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.startRecomputeAll("목 리뷰 적재", OPERATOR, IP))
                .isInstanceOf(IllegalStateException.class);

        assertThat(pending).isEmpty();
        assertThat(service.status().running()).isFalse();
        verifyNoInteractions(bulkRecomputeService);
    }

    @Test
    void 실행기가_거절하면_도는_중으로_남지_않는다() {
        service = new AdminRecomputeService(accessLogService, reviewService, bulkRecomputeService,
                task -> {
                    throw new RejectedExecutionException("busy");
                });

        assertThatThrownBy(() -> service.startRecomputeAll("목 리뷰 적재", OPERATOR, IP))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(service.status().running()).isFalse();
    }

    @Test
    void 작업_자체가_멈추면_중단으로_표시하고_다시_받을_수_있다() {
        willThrow(new IllegalStateException("DB 연결 끊김"))
                .given(bulkRecomputeService).recomputeAll(anyBoolean());

        service.startRecomputeAll("목 리뷰 적재", OPERATOR, IP);
        runPending();

        AdminRecomputeStatus status = service.status();
        assertThat(status.running()).isFalse();
        assertThat(status.hasLastRun()).isTrue();
        assertThat(status.lastAborted()).isTrue();
        assertThat(status.real()).isNull();

        service.startRecomputeAll("다시", OPERATOR, IP);
        assertThat(pending).hasSize(1);
    }

    @Test
    void 실패한_가게는_앞쪽_일부만_화면에_싣는다() {
        List<RecomputeAllResponse.Failure> failures = IntStream.range(0, AdminRecomputeService.FAILURES_SHOWN + 5)
                .mapToObj(i -> new RecomputeAllResponse.Failure(UUID.randomUUID(), "실패 " + i))
                .toList();
        given(bulkRecomputeService.recomputeAll(false))
                .willReturn(new RecomputeAllResponse(30, 5, failures.size(), 5, failures));

        service.startRecomputeAll("목 리뷰 적재", OPERATOR, IP);
        runPending();

        AdminRecomputeStatus.Axis real = service.status().real();
        assertThat(real.hasFailures()).isTrue();
        assertThat(real.failedStoreIds()).hasSize(AdminRecomputeService.FAILURES_SHOWN)
                .first().isEqualTo(failures.get(0).storeId().toString());
        assertThat(real.moreFailures()).isTrue();
        assertThat(service.status().demo().hasFailures()).isFalse();
    }

    @Test
    void 실행한_적이_없으면_마지막_실행이_비어_있다() {
        AdminRecomputeStatus status = service.status();

        assertThat(status.running()).isFalse();
        assertThat(status.hasLastRun()).isFalse();
        assertThat(status.real()).isNull();
    }
}
