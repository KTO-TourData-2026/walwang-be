package com.server.walwang_be.entity;

/** 태그 그룹. 프론트에서 태그를 묶어 보여줄 때 쓴다. */
public enum TagCategory {

    /** 공간 자체의 성격 — 마당, 잔디밭처럼 물리적 조건 */
    SPACE,
    /** 갖춰진 시설·비품 — 급수대, 방석처럼 있고 없고가 갈리는 것 */
    FACILITY,
    /** 분위기 — 조용함, 붐빔처럼 방문해봐야 아는 것 */
    ATMOSPHERE,
    /** 지켜야 하는 규칙 — 시설이나 분위기가 아니라 방문자의 의무 */
    POLICY
}
