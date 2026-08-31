package com.server.walwang_be.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.StoreSizeStatus;

/**
 * 반려견 크기별 동반 가능 상태.
 * status는 저장된 값이 아니라 score로 매번 계산된 결과다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SizeStatusResponse(
        DogSize size,
        AvailabilityStatus status,
        int possibleCount,
        int impossibleCount
) {
    public static SizeStatusResponse from(StoreSizeStatus entity) {
        return new SizeStatusResponse(
                entity.getSize(),
                entity.status(),
                entity.getPossibleCount(),
                entity.getImpossibleCount());
    }
}
