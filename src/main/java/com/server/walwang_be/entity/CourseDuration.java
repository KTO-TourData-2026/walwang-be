package com.server.walwang_be.entity;

/**
 * 코스에 쓸 수 있는 시간.
 *
 * 얼마나 멀리까지 볼지(searchRadius)와 몇 군데를 묶을지(maxStores)를 함께 정한다.
 * 값은 운영하며 조정할 자리라 상수로 모아 둔다.
 */
public enum CourseDuration {

    /** 잠깐 나갔다 오는 정도 */
    SHORT("짧게", 1500, 2),
    /** 반나절 */
    HALF_DAY("반나절", 3000, 3),
    /** 하루 종일 */
    FULL_DAY("하루종일", 5000, 5);

    private final String label;
    /** 지도 중심에서 후보를 찾을 반경(미터). */
    private final int searchRadius;
    /** 코스에 담을 최대 가게 수. */
    private final int maxStores;

    CourseDuration(String label, int searchRadius, int maxStores) {
        this.label = label;
        this.searchRadius = searchRadius;
        this.maxStores = maxStores;
    }

    public String label() {
        return label;
    }

    public int searchRadius() {
        return searchRadius;
    }

    public int maxStores() {
        return maxStores;
    }
}
