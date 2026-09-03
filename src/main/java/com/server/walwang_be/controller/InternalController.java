package com.server.walwang_be.controller;

import com.server.walwang_be.dto.RecomputeResultResponse;
import com.server.walwang_be.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 운영 조치용. 화면에서 부르지 않는다.
 *
 * TODO ROLE_ADMIN이 생기면 이 컨트롤러를 /admin 아래로 옮기고
 *      @PreAuthorize("hasRole('ADMIN')")를 걸 것. 지금은 권한이 ROLE_USER 하나뿐이라
 *      로그인한 사람이면 누구나 부를 수 있다. 재집계는 저장된 리뷰를 다시 세는 것이라
 *      값을 조작하지는 못하지만, 반복 호출로 DB를 긁을 수는 있다.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final ReviewService reviewService;

    /**
     * 가게 하나를 다시 집계한다.
     *
     * 관리자가 blind_status를 DB에서 직접 바꾼 뒤 반드시 이걸 부른다 —
     * 안 부르면 가려진 리뷰가 점수에 그대로 남는다. docs/admin-runbook.md 참고.
     *
     * 응답에 크기별 점수·건수가 들어 있으니 조치가 반영됐는지 눈으로 확인할 수 있다.
     */
    @Operation(summary = "가게 점수 재집계(운영용)",
            description = "리뷰 블라인드 처리 후 호출한다. 화면에서는 쓰지 않는다.")
    @PostMapping("/stores/{storeId}/recompute")
    public ResponseEntity<RecomputeResultResponse> recompute(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID storeId) {
        RecomputeResultResponse result = reviewService.recomputeStore(storeId);
        // 누가 운영 조치를 했는지 남긴다. 권한이 없는 지금은 이 로그가 유일한 흔적이다.
        log.info("재집계 요청: store={} 리뷰 {}건 (요청자 {})",
                storeId, result.countedReviews(), userId);

        return ResponseEntity.ok(result);
    }
}
