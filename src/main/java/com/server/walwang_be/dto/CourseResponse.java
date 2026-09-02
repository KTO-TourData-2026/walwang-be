package com.server.walwang_be.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;

import java.util.List;

/**
 * 코스 한 개. 추천 결과와 저장된 코스 조회가 같은 모양을 쓴다 —
 * 프론트가 추천 화면과 상세 화면을 같은 컴포넌트로 그릴 수 있다.
 *
 * id는 저장된 코스에만 있다. 추천 결과는 아직 저장 전이라 비어 있다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CourseResponse(
        Long id,
        String title,
        DogSize dogSize,
        List<CoursePurpose> purposes,
        CourseDuration duration,
        int totalDistance,
        int totalDuration,
        List<List<Double>> walkPath,
        String description,
        /** 조건에 맞는 곳이 부족해 기준을 낮췄는지. 화면에서 안내 문구를 띄우는 데 쓴다. */
        boolean relaxed,
        List<CourseStoreResponse> stores
) {
}
