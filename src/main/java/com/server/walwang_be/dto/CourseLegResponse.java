package com.server.walwang_be.dto;

/**
 * 코스에서 이웃한 두 지점 사이의 한 구간.
 *
 * fromIndex/toIndex는 같은 응답의 stores 배열 인덱스다 — 화면이 가게 목록을 그리면서
 * 사이사이에 거리와 도보 시간을 끼워 넣을 수 있게 순서를 그대로 따라간다.
 * 지점이 n개면 구간은 항상 n-1개이고, i번째 구간은 (i, i+1)이다.
 *
 * duration은 totalDuration과 같은 분 단위다. 초를 분으로 자르면 짧은 구간이 0분이 되어
 * "바로 옆"처럼 보이므로 올림한다 — 30초든 90초든 걸어야 하는 건 마찬가지라
 * 1분 이상으로 나온다. 머무는 시간은 빼고 걷는 시간만 담는다.
 *
 * @param distance 미터
 * @param duration 분. 올림이라 걷는 구간은 최소 1이고, 건너뛴 구간만 0이다
 */
public record CourseLegResponse(
        int fromIndex,
        int toIndex,
        int distance,
        int duration
) {
}
