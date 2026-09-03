package com.server.walwang_be.service;

import com.server.walwang_be.client.TourApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 한 요청 안에서 쓰는 성동구 공사 데이터.
 *
 * 요청마다 새로 만들어지고 응답이 나가면 버려진다 —
 * 여러 유저에게 재사용되는 캐시를 두면 공사 데이터를 우리가 보관하는 셈이 되어,
 * 저장했다고 볼 수 있는 여지가 생긴다.
 *
 * 같은 요청에서 두 번 이상 필요하면 그때만 결과를 나눠 쓴다.
 * 실패도 기억하지 않는다 — 다음 요청은 다시 시도한다.
 */
@Slf4j
@Component
@RequestScope
@RequiredArgsConstructor
public class TourDataProvider {

    /** 성동구. 지금은 이 지역만 서비스한다. */
    private static final String SEOUL = "11";
    private static final String SEONGDONG = "200";
    /** 성동구 전체가 102건이라 한 번에 받는다. */
    private static final int PAGE_SIZE = 500;

    private final TourApiClient tourApiClient;

    /** 이 요청에서 이미 불렀는지. 성공이든 실패든 한 번만 나간다. */
    private List<Map<String, Object>> items;

    /**
     * 성동구 전체 목록. 실패하면 빈 목록을 준다 —
     * 호출한 쪽은 그걸 "참고 정보 없음"으로 다루고 본래 응답을 그대로 내보낸다.
     */
    public List<Map<String, Object>> seongdongItems() {
        if (items != null) {
            return items;
        }
        try {
            items = tourApiClient
                    .areaBasedList(SEOUL, SEONGDONG, null, PAGE_SIZE, 1, true)
                    .items();
        } catch (RuntimeException e) {
            log.warn("공사 데이터 조회 실패, 빈 목록으로 응답합니다: {}", e.getMessage());
            items = List.of();
        }
        return items;
    }

    /**
     * 좌표가 있는 항목만 매칭용 지점으로 바꾼 것.
     *
     * 지도와 코스가 같은 목록을 쓴다 — 각자 변환하면 같은 요청에서 같은 일을 두 번 한다.
     * 좌표가 없는 항목은 견줄 기준이 없어 버린다.
     */
    public List<TourStoreMatcher.TourPoint> seongdongPoints() {
        List<Map<String, Object>> source = seongdongItems();

        List<TourStoreMatcher.TourPoint> points = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            Double lat = toDouble(item.get("mapy"));
            Double lng = toDouble(item.get("mapx"));
            if (lat != null && lng != null) {
                points.add(new TourStoreMatcher.TourPoint(
                        String.valueOf(item.get("title")), lat, lng));
            }
        }
        return points;
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
}
