package com.server.walwang_be.dto;

import java.util.List;

/** GET /tags 응답. 목록을 tags로 한 번 감싼다. */
public record TagListResponse(List<TagResponse> tags) {
}
