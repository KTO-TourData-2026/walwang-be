package com.server.walwang_be.service;

/**
 * 두 좌표 사이의 거리.
 *
 * 실제 도로를 따라간 거리가 아니라 직선거리다 — 길찾기 API가 붙기 전까지
 * 코스의 대략적인 규모를 잡는 용도로만 쓴다.
 */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoDistance() {
    }

    /** 미터. */
    public static double between(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
