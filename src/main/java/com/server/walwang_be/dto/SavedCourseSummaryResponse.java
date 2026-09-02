package com.server.walwang_be.dto;

import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.SavedCourse;
import com.server.walwang_be.entity.SavedCourseStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 저장한 코스 미리보기 한 건.
 *
 * 경로선(walkPath)과 가게 상세는 빼고 카드에 필요한 것만 담는다 —
 * 목록에서 코스마다 좌표 수백 개를 내려보낼 이유가 없다.
 */
public record SavedCourseSummaryResponse(
        UUID courseId,
        String title,
        int storeCount,
        /** 방문 순서대로. 시연 모드에서는 가명이 들어간다. */
        List<String> storeNames,
        int totalDistance,
        int totalDuration,
        DogSize dogSize,
        Instant createdAt
) {
    public static SavedCourseSummaryResponse of(SavedCourse course, boolean demo) {
        List<String> names = course.getStores().stream()
                .map(SavedCourseStore::getStore)
                .map(store -> store.displayName(demo))
                .toList();

        return new SavedCourseSummaryResponse(
                course.getCourseId(),
                course.getTitle(),
                names.size(),
                names,
                course.getTotalDistance(),
                course.getTotalDuration(),
                course.getDogSize(),
                course.getCreatedAt());
    }
}
