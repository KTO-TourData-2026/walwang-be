package com.server.walwang_be.dto;

import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 코스 저장 요청.
 *
 * 거리·시간·경로를 클라이언트가 그대로 보내는 이유는, 추천 결과를 본 그대로
 * 박제하기 위해서다 — 서버가 다시 계산하면 그 사이 가게 정보가 바뀌었을 때
 * 화면에서 본 것과 다른 값이 저장된다.
 */
public record CourseSaveRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull DogSize dogSize,
        @NotEmpty List<CoursePurpose> purposes,
        @NotNull CourseDuration duration,
        @PositiveOrZero int totalDistance,
        @PositiveOrZero int totalDuration,
        List<List<Double>> walkPath,
        @Size(max = 500) String description,
        /** 순서가 곧 arrivalOrder다. */
        @NotEmpty List<UUID> storeIds
) {
}
