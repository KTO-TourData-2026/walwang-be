package com.server.walwang_be.dto;

/** 지도 좌표. 응답에서 lat/lng를 한 덩어리로 묶어 보낸다. */
public record GeoPoint(double lat, double lng) {
}
