package com.server.walwang_be.dto;

import com.server.walwang_be.entity.AvailabilityStatus;

import java.util.List;
import java.util.UUID;

/**
 * 코스에 담긴 가게 한 곳.
 *
 * 코스 후보는 공사 매칭된 곳으로만 좁히므로 여기 담긴 가게는 전부 확인된 곳이다 —
 * 따로 표시할 필요가 없어 배지 필드를 두지 않는다.
 *
 * name·address 모두 demo가 정한 값이다. 필드 이름은 그대로 두고 값만 갈린다 —
 * 시연 중에는 상세 번지가 실리지 않는다.
 */
public record CourseStoreResponse(
        UUID storeId,
        String name,
        String type,
        double lat,
        double lng,
        String address,
        int arrivalOrder,
        /** 요청한 반려견 크기 기준의 동반 가능 등급. */
        AvailabilityStatus status,
        int reviewCount,
        List<String> tags
) {
}
