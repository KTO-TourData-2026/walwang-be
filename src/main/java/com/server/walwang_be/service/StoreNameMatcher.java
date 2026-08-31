package com.server.walwang_be.service;

/**
 * OCR로 읽은 상호명과 등록된 가게명이 같은 곳인지 판단한다.
 *
 * 완전 일치를 요구할 수 없다 — 영수증에는 "왈왕카페 강남점"처럼 지점이 붙고,
 * OCR은 글자를 종종 흘린다. 그래서 편집거리 기반 유사도로 본다.
 */
public final class StoreNameMatcher {

    /**
     * 이 값 이상이면 같은 가게로 본다.
     * 낮추면 남의 영수증이 통과하고, 높이면 정상 영수증이 자꾸 튕긴다.
     */
    public static final double THRESHOLD = 0.6;

    private StoreNameMatcher() {
    }

    /** 0.0 ~ 1.0. 1.0이면 정규화 후 완전히 같다는 뜻이다. */
    public static double similarity(String storeName, String ocrName) {
        String a = normalize(storeName);
        String b = normalize(ocrName);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        // 영수증의 "왈왕카페 강남점"처럼 한쪽이 다른 쪽을 품는 경우를 편집거리로만 보면
        // 길이 차이 때문에 억울하게 점수가 깎인다.
        if (a.contains(b) || b.contains(a)) {
            return Math.max(0.8, ratio(a, b));
        }
        return ratio(a, b);
    }

    private static double ratio(String a, String b) {
        int distance = levenshtein(a, b);
        int longest = Math.max(a.length(), b.length());
        return 1.0 - ((double) distance / longest);
    }

    /** 공백·괄호·하이픈 등 표기 차이를 걷어낸다. 대소문자도 무시한다. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-_()\\[\\]{}.,'\"]", "").toLowerCase();
    }

    /** 두 행만 들고 도는 방식이라 긴 문자열에도 메모리가 늘지 않는다. */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
