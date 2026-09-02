package com.server.walwang_be.dto;

import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 코스 추천 조건.
 *
 * 다른 API와 달리 필드명이 camelCase인데, 코스 명세가 그렇게 정해져 있어 맞췄다.
 */
public record CourseRecommendRequest(
        @NotNull DogSize dogSize,
        @NotEmpty List<CoursePurpose> purposes,
        @NotNull CourseDuration duration,
        /** 지도 중심 위도 */
        @NotNull Double lat,
        /** 지도 중심 경도 */
        @NotNull Double lng
) {
}
