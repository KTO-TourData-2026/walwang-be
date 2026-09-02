package com.server.walwang_be.controller;

import com.server.walwang_be.client.TourApiClient;
import com.server.walwang_be.service.TourStoreMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3단계 매칭이 실제로 얼마나 붙는지 확인하는 실험용 엔드포인트.
 *
 * 규칙이 확정되면 이 컨트롤러는 지운다. local 프로파일 전용이며
 * 결과는 콘솔에만 남기고 저장하지 않는다.
 */
@Slf4j
@Profile("local")
@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class TourRadiusDebugController {

    private final TourApiClient tourApiClient;
    private final TourStoreMatcher matcher;

    @GetMapping("/tour-staged")
    public ResponseEntity<Map<String, Object>> staged(
            @RequestParam(defaultValue = "11") String lDongRegnCd,
            @RequestParam(defaultValue = "200") String lDongSignguCd,
            @RequestParam(defaultValue = "500") int numOfRows) {

        List<TourStoreMatcher.TourPoint> points = new ArrayList<>();
        for (Map<String, Object> item :
                tourApiClient.areaBasedList(lDongRegnCd, lDongSignguCd, null, numOfRows, 1).items()) {
            Double lat = toDouble(item.get("mapy"));
            Double lng = toDouble(item.get("mapx"));
            if (lat != null && lng != null) {
                points.add(new TourStoreMatcher.TourPoint(
                        String.valueOf(item.get("title")), lat, lng));
            }
        }

        TourStoreMatcher.StagedResult result = matcher.matchAll(points);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tourItemCount", result.total());
        response.put("stages", result.stages().stream().map(stage -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", stage.name());
            row.put("newMatches", stage.newMatches());
            row.put("cumulative", stage.cumulative());
            row.put("pairs", stage.pairs().stream().map(pair -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tourTitle", pair.tourTitle());
                item.put("storeName", pair.match().storeName());
                item.put("distanceMeters", Math.round(pair.match().distanceMeters() * 10) / 10.0);
                item.put("similarity", Math.round(pair.match().similarity() * 100) / 100.0);
                item.put("externalId", pair.match().externalId());
                item.put("address", pair.match().address());
                return item;
            }).toList());
            return row;
        }).toList());
        response.put("droppedDuplicates", result.droppedDuplicates());
        response.put("finalMatchedCount", result.matched().size());
        response.put("finalMatches", result.matched().entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tourTitle", e.getKey());
            m.put("storeId", e.getValue().storeId());
            m.put("externalId", e.getValue().externalId());
            m.put("storeName", e.getValue().storeName());
            m.put("address", e.getValue().address());
            m.put("distanceMeters", Math.round(e.getValue().distanceMeters() * 10) / 10.0);
            m.put("stage", e.getValue().stage());
            return m;
        }).toList());
        // 중복 규칙이 실제로 다 걸렀는지 최종 목록에서 다시 센다.
        Map<String, Long> perStore = result.matched().values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        m -> m.storeId() + " | " + m.storeName(),
                        java.util.stream.Collectors.counting()));
        response.put("storesWithMultipleMatches", perStore.entrySet().stream()
                .filter(e -> e.getValue() > 1).toList());
        response.put("distinctStoreCount", perStore.size());
        response.put("unmatched", result.unmatched().stream()
                .map(TourStoreMatcher.TourPoint::title).toList());

        log.info("=== TOUR STAGED MATCH ===\n{}", response);
        return ResponseEntity.ok(response);
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
