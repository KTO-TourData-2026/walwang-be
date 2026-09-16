package com.server.walwang_be.dto;

import java.util.List;

/**
 * 관리자 재집계 화면의 전체 재집계 상태. Mustache가 그대로 읽는다.
 *
 * 누가 실행했는지는 담지 않는다 — 그건 접속기록에 있고, 이 화면을 개인정보 없는 화면으로 두려는 것이다.
 *
 * @param running        지금 돌고 있는지
 * @param runningSince   돌고 있다면 시작 시각(아직 모르면 "방금"). 돌고 있지 않으면 쓰지 않는다
 * @param hasLastRun     서버가 뜬 뒤 끝난 실행이 있는지. 재시작하면 사라진다
 * @param lastAborted    마지막 실행이 중간에 멈췄는지(가게 단위 실패가 아니라 작업 자체의 실패)
 */
public record AdminRecomputeStatus(
        boolean running,
        String runningSince,
        boolean hasLastRun,
        String lastStartedAt,
        String lastFinishedAt,
        long lastElapsedSeconds,
        boolean lastAborted,
        Axis real,
        Axis demo
) {
    /**
     * 한 축(실제/시연)의 결과.
     *
     * @param failedStoreIds 실패한 가게 id. 화면이 길어지지 않게 앞쪽 일부만 담는다
     * @param moreFailures   failedStoreIds에 다 담지 못했는지
     */
    public record Axis(String label, int total, int succeeded, int failed,
                       boolean hasFailures, List<String> failedStoreIds, boolean moreFailures) {
    }
}
