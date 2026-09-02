package com.server.walwang_be.dto;

import com.server.walwang_be.entity.AvailabilityStatus;

import java.util.List;
import java.util.UUID;

/** 코스에 담긴 가게 한 곳. */
public record CourseStoreResponse(
        UUID storeId,
        String name,
        String type,
        double lat,
        double lng,
        int arrivalOrder,
        /** 요청한 반려견 크기 기준의 동반 가능 등급. */
        AvailabilityStatus status,
        int reviewCount,
        List<String> tags
) {
}
