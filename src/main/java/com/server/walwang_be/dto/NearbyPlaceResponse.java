package com.server.walwang_be.dto;

/**
 * 코스 주변의 참고 장소. 한국관광공사 데이터다.
 *
 * 우리 가게(stores)와 자리를 나눠 둔다 — 저장할 수도 없고 저장되지도 않는 값이라,
 * 섞어 두면 코스에 담긴 지점인지 그냥 근처 볼거리인지 구분이 안 된다.
 */
public record NearbyPlaceResponse(
        String title,
        /** park / festival / attraction */
        String type,
        /**
         * 공사의 addr1(기본주소) + addr2(상세주소)를 한 줄로 합친 것.
         * 둘 다 비어 있으면 null이다 — 주소가 없는 항목이 섞여 있다.
         */
        String address,
        double lat,
        double lng,
        /** 없을 수 있다. 공사 데이터에 사진이 안 붙은 항목이 많다. */
        String imageUrl,
        String source
) {
    public static final String SOURCE = "한국관광공사";
}
