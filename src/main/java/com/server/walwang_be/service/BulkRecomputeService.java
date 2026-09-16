package com.server.walwang_be.service;

import com.server.walwang_be.dto.RecomputeAllResponse;
import com.server.walwang_be.repo.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 리뷰가 있거나 옛 점수·태그가 남은 가게를 한꺼번에 다시 집계한다(ReviewRepository.findRecomputeTargets).
 *
 * 리뷰를 SQL로 직접 넣거나 지우면 등록·삭제 경로를 타지 않아 재집계가 돌지 않는다.
 * 목 리뷰를 넣거나 지운 뒤 가게마다 재집계를 부르는 대신 한 번에 끝내라고 두는 자리다.
 * 리뷰가 모두 사라진 가게는 행을 지우지 않고 0으로 되돌린다 — 행이 없으면 화면이 크기별 상태를 그리지 못한다.
 * 관리자 페이지의 전체 재집계(AdminRecomputeService)가 백그라운드에서 부른다.
 *
 * 일부러 트랜잭션을 걸지 않는다 — 가게마다 ReviewService의 트랜잭션이 따로 열려야
 * 한 곳이 실패해도 그 건만 롤백되고 나머지가 살아남는다.
 * 여기에 트랜잭션을 걸면 실패 하나가 전체를 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkRecomputeService {

    private final ReviewRepository reviewRepository;
    private final ReviewService reviewService;

    /**
     * @param demo true면 시연용 목 리뷰로 집계해 시연 쪽 행(is_demo=true)만 고친다.
     *             실제 쪽 행은 건드리지 않는다. 두 축을 다 맞추려면 두 번 부른다(AdminRecomputeService)
     */
    public RecomputeAllResponse recomputeAll(boolean demo) {
        long startedAt = System.currentTimeMillis();
        // 리뷰가 있는 가게 + 옛 값이 남은 가게. 리뷰가 없는 가게는 재집계가 행을 지우지 않고 0으로 되돌린다.
        List<UUID> storeIds = reviewRepository.findRecomputeTargets(demo);

        List<RecomputeAllResponse.Failure> failures = new ArrayList<>();
        int succeeded = 0;

        for (UUID storeId : storeIds) {
            try {
                // 다른 빈을 거쳐 부르므로 가게마다 트랜잭션이 새로 열린다.
                reviewService.recomputeStore(storeId, demo);
                succeeded++;
            } catch (RuntimeException e) {
                // 한 곳이 막혀도 나머지는 끝까지 돈다. 어디가 막혔는지는 응답으로 돌려준다.
                log.warn("재집계 실패: store={} ({})", storeId, e.toString());
                failures.add(new RecomputeAllResponse.Failure(storeId, e.getMessage()));
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("일괄 재집계(demo={}): 대상 {}곳, 성공 {}곳, 실패 {}곳 ({}ms)",
                demo, storeIds.size(), succeeded, failures.size(), elapsed);

        return new RecomputeAllResponse(
                storeIds.size(), succeeded, failures.size(), elapsed, failures);
    }
}
