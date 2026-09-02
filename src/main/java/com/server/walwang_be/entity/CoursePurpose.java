package com.server.walwang_be.entity;

import java.util.Set;

/**
 * 코스에서 하고 싶은 것. 여러 개를 함께 고를 수 있다.
 *
 * 각 목적이 어떤 가게 종류와 이어지는지는 storeTypes에 둔다.
 * 지금은 이 값으로 후보를 거르지만, 추천이 벡터 기반으로 바뀌면
 * 이 매핑 대신 임베딩이 그 역할을 하게 된다.
 */
public enum CoursePurpose {

    /** 산책. 공원·산책로처럼 걷는 게 목적인 곳 */
    WALK("산책", Set.of("PARK", "TRAIL", "PLAYGROUND")),
    /** 카페 */
    CAFE("카페", Set.of("CAFE")),
    /** 식사 */
    RESTAURANT("식사", Set.of("RESTAURANT")),
    /** 쇼핑 */
    SHOPPING("쇼핑", Set.of("SHOPPING", "MALL")),
    /** 놀이터·운동장처럼 뛰어놀 수 있는 곳 */
    PLAY("놀이", Set.of("PLAYGROUND", "DOG_PARK"));

    private final String label;
    private final Set<String> storeTypes;

    CoursePurpose(String label, Set<String> storeTypes) {
        this.label = label;
        this.storeTypes = storeTypes;
    }

    /** 가게 type이 이 목적에 해당하는지. 외부 API 값이라 대소문자는 무시한다. */
    public boolean matches(String storeType) {
        if (storeType == null) {
            return false;
        }
        return storeTypes.stream().anyMatch(type -> type.equalsIgnoreCase(storeType));
    }

    public String label() {
        return label;
    }
}
