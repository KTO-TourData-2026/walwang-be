package com.server.walwang_be.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.server.walwang_be.entity.AvailabilityStatus;

import java.util.List;
import java.util.UUID;

/**
 * 코스에 담긴 지점 한 곳. 첫 항목은 출발점이고, 그 뒤가 가게다.
 *
 * 코스 후보는 공사 매칭된 곳으로만 좁히므로 여기 담긴 가게는 전부 확인된 곳이다 —
 * 따로 표시할 필요가 없어 배지 필드를 두지 않는다.
 *
 * name·address 모두 demo가 정한 값이다. 필드 이름은 그대로 두고 값만 갈린다 —
 * 시연 중에는 상세 번지가 실리지 않는다.
 *
 * 출발점은 가게가 아니다. storeId가 {@link #START_POINT_ID}이고 type·address·status는 null이다.
 * null 필드도 빠지지 않고 실린다 — 추천과 조회가 키까지 같은 모양이어야 한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CourseStoreResponse(
        UUID storeId,
        String name,
        String type,
        double lat,
        double lng,
        String address,
        /**
         * 출발점은 0, 가게는 방문 순서대로 1부터.
         * saved_course_stores.arrival_order를 옮긴 값이 아니라 응답 목록에서 매긴 순번이다
         * (StoreCandidates.toResponses). DB도 가게만 1부터 세어 지금은 값이 같지만, 둘은 별개다 —
         * 출발점과 첫 가게가 같은 장소여도 0과 1로 갈려 화면이 구분할 수 있다.
         */
        int arrivalOrder,
        /** 요청한 반려견 크기 기준의 동반 가능 등급. 출발점은 null. */
        AvailabilityStatus status,
        int reviewCount,
        List<String> tags
) {

    /** 출발점의 storeId. 실제 가게와 겹치지 않는 nil UUID라 프론트가 이 값으로 가른다. */
    public static final UUID START_POINT_ID = new UUID(0L, 0L);

    public static CourseStoreResponse startPoint(double lat, double lng) {
        return new CourseStoreResponse(START_POINT_ID, "출발점", null, lat, lng, null,
                0, null, 0, List.of());
    }
}
