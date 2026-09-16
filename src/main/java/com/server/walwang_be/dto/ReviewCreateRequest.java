package com.server.walwang_be.dto;

import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.StoreTag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * multipart의 data 파트로 들어오는 리뷰 본문.
 *
 * is_demo는 여기에 없다 — 본문이 아니라 요청 파라미터(demo)로 받는다.
 */
public record ReviewCreateRequest(
        @NotNull UUID storeId,
        /**
         * 영수증 인증 토큰. @NotBlank를 걸지 않는 이유는 시연 모드(demo=true)와
         * 거절 리뷰(dogAllowed=false)가 인증 없이 오기 때문이다 — 여기서 막으면
         * 서비스까지 오지도 못한다. 그 밖의 경로에서 비어 있으면 ReviewService가 400으로 끊는다.
         */
        String receiptToken,
        @NotNull Boolean dogAllowed,
        @NotNull DogSize dogSize,
        @Size(max = 1000) String content,
        /** 선택한 태그 code 목록. 없으면 빈 배열. 예: ["WIDE_YARD", "FENCED"] */
        List<StoreTag> tags
) {
}
