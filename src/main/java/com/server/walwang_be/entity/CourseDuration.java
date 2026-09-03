package com.server.walwang_be.entity;

/**
 * 코스에 쓸 수 있는 시간.
 *
 * 얼마나 멀리까지 볼지(searchRadius), 몇 군데를 묶을지(maxStores),
 * 얼마나 걷게 둘지(maxWalkMeters)를 함께 정한다.
 * 값은 운영하며 조정할 자리라 상수로 모아 둔다.
 */
public enum CourseDuration {

    /** 잠깐 나갔다 오는 정도 */
    SHORT("짧게", 1500, 2, 3000),
    /** 반나절 */
    HALF_DAY("반나절", 3000, 3, 6000),
    /** 하루 종일 */
    FULL_DAY("하루종일", 5000, 5, 10000);

    private final String label;
    /** 지도 중심에서 후보를 찾을 반경(미터). */
    private final int searchRadius;
    /** 코스에 담을 최대 가게 수. */
    private final int maxStores;
    /**
     * 코스 전체 도보 거리의 상한(미터).
     *
     * searchRadius는 지도 중심에서 후보를 고르는 반경일 뿐이라 총 도보 거리를 묶지 못한다 —
     * 반경 끝에 있는 두 곳을 고르면 그 둘 사이가 반경의 두 배까지 벌어진다.
     * 유사도가 아무리 높아도 하루가 걸릴 코스는 코스가 아니므로 여기서 끊는다.
     */
    private final int maxWalkMeters;

    CourseDuration(String label, int searchRadius, int maxStores, int maxWalkMeters) {
        this.label = label;
        this.searchRadius = searchRadius;
        this.maxStores = maxStores;
        this.maxWalkMeters = maxWalkMeters;
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

    public int maxWalkMeters() {
        return maxWalkMeters;
    }
}
