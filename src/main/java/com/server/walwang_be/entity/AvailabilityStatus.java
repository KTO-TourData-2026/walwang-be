package com.server.walwang_be.entity;

/**
 * 동반 가능 여부 등급.
 *
 * DB에 저장하지 않고 score로 매번 계산한다 —
 * 임계값을 코드 상수로 둬야 나중에 조정할 수 있기 때문이다.
 */
public enum AvailabilityStatus {

    /** 가능 쪽 점수가 우세 */
    POSSIBLE,
    /** 불가 쪽 점수가 우세 */
    IMPOSSIBLE,
    /** 표본이 부족하거나 양쪽이 팽팽함 */
    UNKNOWN;

    /**
     * 등급을 매기기 위한 최소 점수 합.
     * 리뷰 한두 건으로 단정하지 않으려고 둔 값이며, 운영하며 조정한다.
     */
    private static final double MIN_TOTAL_SCORE = 1.0;

    public static AvailabilityStatus of(double possibleScore, double impossibleScore) {
        if (possibleScore + impossibleScore < MIN_TOTAL_SCORE) {
            return UNKNOWN;
        }
        if (possibleScore > impossibleScore) {
            return POSSIBLE;
        }
        if (impossibleScore > possibleScore) {
            return IMPOSSIBLE;
        }
        // 동점이면 어느 쪽도 아니다. 섣불리 가능이라고 답하지 않는다.
        return UNKNOWN;
    }
}
