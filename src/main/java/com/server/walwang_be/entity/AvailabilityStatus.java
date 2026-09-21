package com.server.walwang_be.entity;

/**
 * 동반 가능 여부 등급.
 *
 * DB에 저장하지 않고 score·count로 매번 계산한다 —
 * 임계값을 코드 상수로 둬야 나중에 조정할 수 있기 때문이다.
 */
public enum AvailabilityStatus {

    /** 가능 쪽이 건수와 비율을 모두 채움 */
    POSSIBLE,
    /** 불가 쪽이 건수와 비율을 모두 채움 */
    IMPOSSIBLE,
    /** 표본이 부족하거나 양쪽이 팽팽함 */
    UNKNOWN;

    /**
     * 등급을 매기기 위한 최소 리뷰 건수.
     *
     * 점수가 아니라 건수로 두는 이유는 시간가중 점수가 시간이 지나면 저절로 줄어드는 값이라서다.
     * 총점에 하한을 두면 리뷰가 그대로인 가게가 아무 일도 없이 UNKNOWN으로 떨어지고,
     * 그 전이는 재집계가 돌기 전까지 반영되지도 않는다. 건수는 늙지 않는다.
     *
     * 불가 쪽을 낮게 두는 것은 거절 한두 건으로 가게를 추천에서 빼지 않으려는 것이다 —
     * 정말 동반이 안 되는 곳이면 거절 리뷰가 더 쌓여 이 수를 채운다.
     */
    private static final int MIN_POSSIBLE_COUNT = 4;
    private static final int MIN_IMPOSSIBLE_COUNT = 3;

    /**
     * 등급을 매기기 위한 최소 점수 비율.
     *
     * 절대 점수가 아니라 비율로 본다. 비율은 분자와 분모가 함께 늙어서
     * 시간이 지나도 값이 거의 움직이지 않는다 — 저장된 점수가 낡아도 등급이 덜 흔들린다.
     *
     * 두 값의 합이 1을 넘으므로 양쪽이 동시에 성립하는 일은 없다.
     */
    private static final double MIN_POSSIBLE_RATIO = 0.70;
    private static final double MIN_IMPOSSIBLE_RATIO = 0.55;

    public static AvailabilityStatus of(double possibleScore, double impossibleScore,
                                        int possibleCount, int impossibleCount) {
        double totalScore = possibleScore + impossibleScore;
        // 건수를 비율보다 먼저 본다. 리뷰가 없는 행은 총점이 0이라 비율을 먼저 계산하면 0으로 나눈다 —
        // 가게마다 0인 행을 미리 만들어 두므로(seed-store-size-status.sql) 그 경우가 대부분이다.
        // 건수가 0이면 아래 두 조건에서 모두 걸러진다.
        if (possibleCount >= MIN_POSSIBLE_COUNT
                && totalScore > 0 && possibleScore / totalScore >= MIN_POSSIBLE_RATIO) {
            return POSSIBLE;
        }
        if (impossibleCount >= MIN_IMPOSSIBLE_COUNT
                && totalScore > 0 && impossibleScore / totalScore >= MIN_IMPOSSIBLE_RATIO) {
            return IMPOSSIBLE;
        }
        // 어느 쪽도 기준을 채우지 못했다. 섣불리 가능이라고 답하지 않는다.
        return UNKNOWN;
    }
}
