package com.server.walwang_be.service;

import java.util.List;

/**
 * 두 데이터의 가게 이름 표기를 맞춘다.
 *
 * 관광공사는 "브랜드 + 지점"("데우스엑스마키나 성수점"), 소상공인 데이터는
 * 법인·매장명을 붙여 쓴다("데우스엑스마키나성수동"). 같은 곳인데도 글자가 달라
 * 유사도가 낮게 나오므로, 비교 전에 흔들리는 부분을 걷어낸다.
 *
 * 모든 치환은 부분 문자열 기준이다 — 우리 DB 이름은 공백이 거의 없어
 * 토큰 단위로는 걸러지지 않기 때문이다.
 */
final class StoreNameNormalizer {

    /**
     * 지역명. "성수점"·"성수동"·"성수"가 모두 "성수"가 되게 통일한다.
     * 긴 것부터 지워야 "성수동"이 "성수"+"동"으로 어긋나지 않는다.
     */
    private static final List<String> REGIONS = List.of(
            "왕십리", "성수", "금호", "옥수", "행당", "응봉", "마장", "송정", "용답");

    /** 건물·입점처 이름. 한쪽에만 붙어 있어 비교를 방해한다. */
    private static final List<String> BUILDINGS = List.of(
            "아크로서울포레스트", "왕십리민자역사", "비트플렉스", "엔터식스",
            "성수연방", "롯데마트", "이마트");

    /**
     * 업종·매장형태를 가리키는 말.
     * "스탠드오일슈즈플래그십스토어"에서 브랜드만 남기기 위한 것이다.
     */
    private static final List<String> CATEGORY_WORDS = List.of(
            "기프트샵", "플래그십", "플래그쉽", "부티크", "스토어",
            "슈즈", "안경", "커피", "매장", "샵");

    private StoreNameNormalizer() {
    }

    /**
     * 비교용으로 다듬는다. 순서가 중요하다 —
     * 건물명을 먼저 지워야 그 안에 든 지역명이 남지 않는다.
     */
    static String canonicalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value;

        for (String building : BUILDINGS) {
            result = result.replace(building, " ");
        }
        for (String word : CATEGORY_WORDS) {
            result = result.replace(word, " ");
        }
        for (String region : REGIONS) {
            // "성수점"·"성수동"을 "성수"로 모은다.
            result = result.replace(region + "점", region)
                    .replace(region + "동", region);
        }
        return result.trim().replaceAll("\\s+", " ");
    }

    /**
     * 지역명을 아예 걷어낸다. 유사도 계산에만 쓴다.
     *
     * 성수동에 있는 가게는 양쪽 이름에 "성수"가 공통으로 들어가는데,
     * 그 겹침이 유사도를 떠받쳐 "이솝 성수"와 "이구홈성수"가 붙어버렸다.
     * 브랜드명만 남겨 비교하면 그런 우연이 사라진다.
     *
     * 완전일치·접두어 단계에는 쓰지 않는다 — 거기서는 지역명까지 포함해
     * 글자가 맞아떨어지는 것이 곧 근거이기 때문이다.
     */
    static String stripRegions(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value;
        for (String region : REGIONS) {
            result = result.replace(region, " ");
        }
        return result.trim().replaceAll("\\s+", " ");
    }
}
