package com.server.walwang_be.dto;

import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 코스 저장 요청.
 *
 * 거리·시간·경로는 받지 않는다. 티맵에서 얻은 값이라 24시간을 넘겨 보관할 수 없어
 * 저장하지 않고, 조회할 때마다 다시 계산해 내려준다.
 *
 * 출발 좌표는 storeIds와 같은 방식으로, 추천 응답 stores[0](출발점)의 lat/lng를 그대로 돌려받는다.
 * 없으면 조회 때 첫 가게에서 출발한 것으로 본다.
 */
public record CourseSaveRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull DogSize dogSize,
        @NotEmpty List<CoursePurpose> purposes,
        @NotNull CourseDuration duration,
        @Size(max = 500) String description,
        /** 순서가 곧 arrivalOrder다. 출발점의 nil UUID가 섞여 오면 건너뛴다. */
        @NotEmpty List<UUID> storeIds,
        /** 출발점 위도. 추천 응답 stores[0].lat */
        Double startLat,
        /** 출발점 경도. 추천 응답 stores[0].lng */
        Double startLng
) {
}
