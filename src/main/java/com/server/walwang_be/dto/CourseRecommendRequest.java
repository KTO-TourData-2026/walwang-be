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
 *
 * tags는 선택이다 — 있으면 AI에서 기준 벡터를 받아 유사도순으로 줄을 세우고,
 * 없으면 거리순으로 간다. 없다고 추천이 실패하지는 않으므로 필수로 두지 않는다.
 *
 * enum이 아니라 문자열로 받는다. 우리가 모르는 코드가 하나 섞였다고
 * 추천 요청 전체가 400으로 끝나면 안 되기 때문이다 — 그 값은 AI가 판단한다.
 */
public record CourseRecommendRequest(
        @NotNull DogSize dogSize,
        @NotEmpty List<CoursePurpose> purposes,
        @NotNull CourseDuration duration,
        /** 지도 중심 위도 */
        @NotNull Double lat,
        /** 지도 중심 경도 */
        @NotNull Double lng,
        /** 분위기·시설 태그 코드(StoreTag 이름). 없거나 비어 있으면 벡터 정렬을 건너뛴다 */
        List<String> tags
) {
}
