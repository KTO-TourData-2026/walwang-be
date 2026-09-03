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

    /**
     * 산책. 공원·산책로처럼 걷는 게 목적인 곳.
     *
     * 세 값 모두 현재 성동구 데이터에 없다 — 이 목적으로 고르면 후보가 0이라
     * 다음 단계로 완화되어 거리순으로 채워진다(relaxed=true).
     */
    WALK("산책", Set.of("PARK", "TRAIL", "PLAYGROUND")),
    /** 카페. cafe 로 들어와 있다. */
    CAFE("카페", Set.of("CAFE")),
    /** 식사. restaurant 로 들어와 있다. */
    RESTAURANT("식사", Set.of("RESTAURANT")),
    /** 쇼핑. shopping 으로 들어와 있다. MALL 은 현재 성동구 데이터에 없다. */
    SHOPPING("쇼핑", Set.of("SHOPPING", "MALL")),
    /**
     * 놀이터·운동장처럼 뛰어놀 수 있는 곳.
     *
     * 두 값 모두 현재 성동구 데이터에 없다 — WALK 과 같은 이유로 완화 단계로 넘어간다.
     */
    PLAY("놀이", Set.of("PLAYGROUND", "DOG_PARK"));

    private final String label;
    private final Set<String> storeTypes;

    CoursePurpose(String label, Set<String> storeTypes) {
        this.label = label;
        this.storeTypes = storeTypes;
    }

    /**
     * 가게 type이 이 목적에 해당하는지.
     *
     * DB의 type은 소상공인 분류를 그대로 받아 소문자(cafe/restaurant/shopping)로 들어 있고
     * 여기 상수는 대문자다. 수집원이 바뀌어도 흔들리지 않게 대소문자를 무시하고 견준다.
     */
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
