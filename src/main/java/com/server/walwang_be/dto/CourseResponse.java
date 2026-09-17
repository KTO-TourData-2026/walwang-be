package com.server.walwang_be.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;

import java.util.List;
import java.util.UUID;

/**
 * 코스 한 개. 추천 결과와 저장된 코스 조회가 같은 모양을 쓴다 —
 * 프론트가 추천 화면과 상세 화면을 같은 컴포넌트로 그릴 수 있다.
 *
 * id는 저장된 코스에만 있다. 추천 결과는 아직 저장 전이라 비어 있다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CourseResponse(
        UUID id,
        String title,
        DogSize dogSize,
        List<CoursePurpose> purposes,
        CourseDuration duration,
        int totalDistance,
        int totalDuration,
        List<List<Double>> walkPath,
        /**
         * 지점 사이 구간별 거리·도보시간. stores 인덱스를 따라 (0,1), (1,2), ... 순으로,
         * 지점이 n개면 n-1개다. 첫 구간은 출발점 -> 첫 가게이고, 구간 합이 totalDistance다.
         * 티맵을 못 받으면 walkPath와 함께 비어 있다.
         */
        List<CourseLegResponse> legs,
        String description,
        /** 조건에 맞는 곳이 부족해 기준을 낮췄는지. 화면에서 안내 문구를 띄우는 데 쓴다. */
        boolean relaxed,
        /**
         * [0]은 출발점(storeId가 nil UUID), 그 뒤가 방문 순서대로의 가게다.
         * 갈 곳이 없으면 출발점도 없이 비어 있다.
         */
        List<CourseStoreResponse> stores,
        /** 코스 주변의 참고 장소(공사 데이터). 코스 지점과 섞지 않는다. */
        List<NearbyPlaceResponse> nearby
) {
}
