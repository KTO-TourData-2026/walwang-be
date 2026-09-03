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
 */
public record CourseSaveRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull DogSize dogSize,
        @NotEmpty List<CoursePurpose> purposes,
        @NotNull CourseDuration duration,
        @Size(max = 500) String description,
        /** 순서가 곧 arrivalOrder다. */
        @NotEmpty List<UUID> storeIds
) {
}
