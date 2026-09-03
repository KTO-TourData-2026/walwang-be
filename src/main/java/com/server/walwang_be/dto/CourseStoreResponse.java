package com.server.walwang_be.dto;

import com.server.walwang_be.entity.AvailabilityStatus;

import java.util.List;
import java.util.UUID;

/**
 * 코스에 담긴 가게 한 곳.
 *
 * 지도(GET /stores)와 달리 코스는 공사 매칭 여부로 거르지 않는다 —
 * 매칭이 37곳뿐이라 걸러내면 코스가 안 나온다. 대신 petVerified로 표시만 한다.
 */
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
        List<String> tags,
        /**
         * 한국관광공사 반려동물 동반 목록에 이름이 오른 곳인지. 카드에 배지로 쓴다.
         *
         * false는 "동반 불가"가 아니라 "공사 목록에서 확인되지 않음"이다.
         * 동반 가능 여부 자체는 status가 따로 말한다.
         */
        boolean petVerified
) {
}
