package com.server.walwang_be.dto;

import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.StoreTag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * multipart의 data 파트로 들어오는 리뷰 본문.
 *
 * is_mock은 여기에 없다 — 클라이언트가 정할 값이 아니라 서버가 항상 false로 박는다.
 */
public record ReviewCreateRequest(
        @NotNull UUID storeId,
        @NotBlank String receiptToken,
        @NotNull Boolean dogAllowed,
        @NotNull DogSize dogSize,
        @Size(max = 1000) String content,
        /** 선택한 태그 code 목록. 없으면 빈 배열. 예: ["WIDE_YARD", "FENCED"] */
        List<StoreTag> tags
) {
}
