package com.server.walwang_be.service;

import com.server.walwang_be.dto.NearbyPlaceResponse;
import com.server.walwang_be.entity.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 코스 주변의 볼거리를 공사 데이터에서 골라낸다.
 *
 * 코스에 담을 지점이 아니라 "가는 길에 이런 것도 있다"는 참고 정보다.
 * 조회한 데이터는 응답에만 싣고 저장하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NearbyPlaceFinder {

    /** 관광지. 공원이 여기 들어 있다. */
    private static final String TYPE_ATTRACTION = "12";
    /** 축제·공연·행사. */
    private static final String TYPE_FESTIVAL = "15";

    /**
     * 공원으로 볼 분류 코드(신 분류체계 lclsSystm3).
     *
     * cat1~3은 성동구 102건 중 98건이 비어 있어 쓸 수 없다.
     * VE0301 도시공원 / VE0302·VE0304 근린공원 / VE0305 대형·테마공원까지를 공원으로 본다.
     * 산(NA01)·수목원(NA04)·둘레길(NA05)은 공원이 아니라 자연으로 남겨 attraction이 된다.
     */
    private static final Set<String> PARK_PREFIXES = Set.of("VE0301", "VE0302", "VE0304", "VE0305");

    /** 코스 지점을 감싼 사각형에서 이만큼 넓혀 본다. 걸어서 들를 만한 거리다. */
    private static final double PADDING_METERS = 500;
    /** 참고 정보라 너무 많이 실어 보내지 않는다. */
    private static final int LIMIT = 20;

    private final TourDataProvider tourDataProvider;

    /**
     * 코스 지점들을 감싼 범위 안의 참고 장소.
     *
     * 실패하거나 아무것도 없으면 빈 목록이다 — 코스 자체는 그대로 나간다.
     */
    public List<NearbyPlaceResponse> around(List<Store> route) {
        if (route.isEmpty()) {
            return List.of();
        }
        Box box = Box.around(route, PADDING_METERS);

        return tourDataProvider.seongdongItems().stream()
                .filter(item -> {
                    String type = String.valueOf(item.get("contenttypeid"));
                    return TYPE_ATTRACTION.equals(type) || TYPE_FESTIVAL.equals(type);
                })
                .map(this::toPlace)
                .filter(place -> place != null && box.contains(place.lat(), place.lng()))
                .limit(LIMIT)
                .toList();
    }

    /** 좌표가 없는 항목은 지도에 찍을 수 없어 버린다. */
    private NearbyPlaceResponse toPlace(Map<String, Object> item) {
        Double lat = toDouble(item.get("mapy"));
        Double lng = toDouble(item.get("mapx"));
        if (lat == null || lng == null) {
            return null;
        }
        String image = String.valueOf(item.getOrDefault("firstimage", ""));

        return new NearbyPlaceResponse(
                String.valueOf(item.get("title")),
                resolveType(item),
                lat,
                lng,
                image.isBlank() ? null : image,
                NearbyPlaceResponse.SOURCE);
    }

    private String resolveType(Map<String, Object> item) {
        if (TYPE_FESTIVAL.equals(String.valueOf(item.get("contenttypeid")))) {
            return "festival";
        }
        String code = String.valueOf(item.getOrDefault("lclsSystm3", ""));
        boolean park = PARK_PREFIXES.stream().anyMatch(code::startsWith);
        return park ? "park" : "attraction";
    }

    /** mapx/mapy는 JSON에서 문자열로 온다. */
    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 코스 지점을 감싼 사각형. 위경도 1도가 몇 미터인지로 여백을 환산한다. */
    private record Box(double minLat, double maxLat, double minLng, double maxLng) {

        private static final double METERS_PER_LAT_DEGREE = 111_000;

        static Box around(List<Store> route, double paddingMeters) {
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLng = Double.MAX_VALUE;
            double maxLng = -Double.MAX_VALUE;

            for (Store store : route) {
                minLat = Math.min(minLat, store.getLat());
                maxLat = Math.max(maxLat, store.getLat());
                minLng = Math.min(minLng, store.getLng());
                maxLng = Math.max(maxLng, store.getLng());
            }
            double latPadding = paddingMeters / METERS_PER_LAT_DEGREE;
            // 경도 1도의 거리는 위도에 따라 줄어든다. 코스 한가운데 위도로 환산한다.
            double lngPadding = latPadding / Math.cos(Math.toRadians((minLat + maxLat) / 2));

            return new Box(minLat - latPadding, maxLat + latPadding,
                    minLng - lngPadding, maxLng + lngPadding);
        }

        boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }
    }
}
