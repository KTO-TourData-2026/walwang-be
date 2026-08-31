package com.server.walwang_be.entity;

/**
 * 가게 태그. 선택형이라 마스터 테이블 대신 enum으로 고정한다.
 *
 * 프론트와 주고받는 식별자도 이 상수 이름(code)이다. 별도의 숫자 id를 두지 않는 이유는
 * DB에 저장되는 값이 이미 이 이름이기 때문이다 — 번호를 끼워 넣으면 같은 개념에
 * 식별자가 둘이 되고, 그 둘을 잇는 표가 어긋나도 아무도 알아채지 못한다.
 *
 * label(넓은마당)은 화면에 보여줄 글자일 뿐이라 자유롭게 바꿔도 저장된 데이터에 영향이 없다.
 * '#'은 넣지 않는다. 해시태그로 보일지는 화면이 정할 일이다.
 */
public enum StoreTag {

    WIDE_YARD("넓은마당", TagCategory.SPACE),
    LAWN("잔디밭", TagCategory.SPACE),
    DOG_ONLY_AREA("반려견전용공간", TagCategory.SPACE),
    SPACIOUS_TERRACE("테라스넓음", TagCategory.SPACE),
    GOOD_FOR_PLAY("뛰놀기좋아요", TagCategory.SPACE),

    FENCED("펜스완비", TagCategory.FACILITY),
    EASY_PARKING("주차편해요", TagCategory.FACILITY),
    WATER_STATION("급수대있음", TagCategory.FACILITY),
    POOP_BAGS("배변봉투제공", TagCategory.FACILITY),
    DOG_CUSHION("강아지방석", TagCategory.FACILITY),
    DOG_MENU("반려견메뉴", TagCategory.FACILITY),
    DOG_BOWL("강아지식기제공", TagCategory.FACILITY),

    QUIET("조용해요", TagCategory.ATMOSPHERE),
    NOT_CROWDED("한적해요", TagCategory.ATMOSPHERE),
    SHY_DOG_FRIENDLY("소심견도편해요", TagCategory.ATMOSPHERE),
    MANY_DOG_FRIENDS("친구들많아요", TagCategory.ATMOSPHERE),
    GOOD_FIRST_OUTING("첫나들이추천", TagCategory.ATMOSPHERE),
    GOOD_FOR_LONG_STAY("오래머물기좋아요", TagCategory.ATMOSPHERE),

    LEASH_REQUIRED("목줄필요해요", TagCategory.POLICY);

    private final String label;
    private final TagCategory category;

    StoreTag(String label, TagCategory category) {
        this.label = label;
        this.category = category;
    }

    /** 프론트와 주고받는 식별자. 상수 이름 그대로다. */
    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public TagCategory category() {
        return category;
    }
}
