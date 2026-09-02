package com.server.walwang_be.dto;

import java.util.List;

/** 저장한 코스 목록. 명세대로 courses로 한 번 감싼다. */
public record SavedCourseListResponse(List<SavedCourseSummaryResponse> courses) {
}
