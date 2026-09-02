package com.server.walwang_be.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 저장한 코스의 제목 변경.
 * 길이는 저장 때와 같은 100자로 맞춘다 — 더 받으면 DB에서 잘린다.
 */
public record CourseRenameRequest(
        @NotBlank @Size(max = 100) String title
) {
}
