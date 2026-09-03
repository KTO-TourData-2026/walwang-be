package com.server.walwang_be.dto;

import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.StoreSizeStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 재집계 결과. 관리자가 눈으로 확인하는 용도라 점수까지 그대로 보여준다.
 *
 * 화면용 응답과 달리 possibleScore/impossibleScore를 감추지 않는다 —
 * status만 보면 "가린 리뷰가 빠졌는지"를 알 수 없다. 건수가 줄었는지 점수가 줄었는지
 * 둘 다 보여야 조치가 반영됐는지 판단할 수 있다.
 */
public record RecomputeResultResponse(
        UUID storeId,
        String storeName,
        /** 이번 집계에 실제로 쓰인 리뷰 수(정상 상태, 시연 리뷰 제외). */
        int countedReviews,
        List<SizeResult> sizes
) {
    public record SizeResult(
            DogSize size,
            AvailabilityStatus status,
            double possibleScore,
            double impossibleScore,
            int possibleCount,
            int impossibleCount,
            Instant lastVerifiedAt
    ) {
        public static SizeResult from(StoreSizeStatus entity) {
            return new SizeResult(
                    entity.getSize(),
                    entity.status(),
                    entity.getPossibleScore(),
                    entity.getImpossibleScore(),
                    entity.getPossibleCount(),
                    entity.getImpossibleCount(),
                    entity.getLastVerifiedAt());
        }
    }
}
