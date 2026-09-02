package com.server.walwang_be.controller;

import com.server.walwang_be.client.TourApiClient;
import com.server.walwang_be.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관광공사 데이터와 우리 stores 테이블을 눈으로 대조하기 위한 임시 엔드포인트.
 *
 * 매칭 규칙을 정하기 전에 "표기가 실제로 얼마나 어긋나는지" 확인하는 게 목적이라,
 * 여기서는 판정하지 않고 차이만 드러낸다.
 *
 * local 프로파일 전용이며, 결과는 콘솔에만 남기고 저장하지 않는다.
 */
@Slf4j
@Profile("local")
@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class TourMatchDebugController {

    private final TourApiClient tourApiClient;
    private final StoreRepository storeRepository;

    @GetMapping("/tour-match")
    public ResponseEntity<Map<String, Object>> match(
            @RequestParam(defaultValue = "11") String lDongRegnCd,
            @RequestParam(defaultValue = "200") String lDongSignguCd,
            @RequestParam(defaultValue = "500") int numOfRows) {

        TourApiClient.TourResponse tour =
                tourApiClient.areaBasedList(lDongRegnCd, lDongSignguCd, null, numOfRows, 1);

        // 우리 DB 이름은 정규화 형태로도 들고 있어야 표기 차이를 짚어낼 수 있다.
        List<String> storeNames = storeRepository.findAllNames();
        Set<String> exact = Set.copyOf(storeNames);
        Map<String, String> normalized = new HashMap<>();
        storeNames.forEach(name -> normalized.putIfAbsent(normalize(name), name));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tourTotalCount", tour.totalCount());
        result.put("tourReceived", tour.items().size());
        result.put("storeCount", storeNames.size());
        result.put("typeDistribution", distribution(tour.items()));
        result.put("restaurants", detail(tour.items(), "39"));
        result.put("shopping", detail(tour.items(), "38"));
        result.put("parks", parks(tour.items()));
        result.put("nameMatch", compare(tour.items(), exact, normalized));

        log.info("=== TOUR MATCH SUMMARY ===\n{}", result);
        return ResponseEntity.ok(result);
    }

    private Map<String, Long> distribution(List<Map<String, Object>> items) {
        return items.stream().collect(Collectors.groupingBy(
                item -> String.valueOf(item.get("contenttypeid")),
                LinkedHashMap::new, Collectors.counting()));
    }

    private List<Map<String, Object>> detail(List<Map<String, Object>> items, String typeId) {
        return items.stream()
                .filter(item -> typeId.equals(String.valueOf(item.get("contenttypeid"))))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", item.get("title"));
                    row.put("addr1", item.get("addr1"));
                    row.put("mapx", item.get("mapx"));
                    row.put("mapy", item.get("mapy"));
                    return row;
                })
                .toList();
    }

    /** 관광지 중 공원류(A02020700). */
    private List<Object> parks(List<Map<String, Object>> items) {
        return items.stream()
                .filter(item -> "12".equals(String.valueOf(item.get("contenttypeid"))))
                .filter(item -> "A02020700".equals(String.valueOf(item.get("cat3"))))
                .map(item -> item.get("title"))
                .toList();
    }

    /**
     * 39·38 항목 이름을 우리 DB와 대조한다.
     *
     * 정확히 같은 것, 정규화하면 같아지는 것(=표기 차이), 아예 없는 것으로 나눈다.
     * 정규화로 걸리는 항목이 곧 "어떤 규칙을 넣어야 하는지"를 알려준다.
     */
    private Map<String, Object> compare(List<Map<String, Object>> items,
                                        Set<String> exact,
                                        Map<String, String> normalized) {
        List<String> targets = items.stream()
                .filter(item -> {
                    String type = String.valueOf(item.get("contenttypeid"));
                    return "39".equals(type) || "38".equals(type);
                })
                .map(item -> String.valueOf(item.get("title")))
                .toList();

        List<String> exactHits = new ArrayList<>();
        List<Map<String, String>> normalizedHits = new ArrayList<>();
        List<String> misses = new ArrayList<>();

        for (String title : targets) {
            if (exact.contains(title)) {
                exactHits.add(title);
                continue;
            }
            String hit = normalized.get(normalize(title));
            if (hit != null) {
                normalizedHits.add(Map.of("tour", title, "store", hit));
                continue;
            }
            misses.add(title);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetCount", targets.size());
        result.put("exactMatchCount", exactHits.size());
        result.put("exactMatches", exactHits);
        result.put("normalizedMatchCount", normalizedHits.size());
        result.put("normalizedMatches", normalizedHits);
        result.put("missCount", misses.size());
        result.put("misses", misses);
        return result;
    }

    /** 공백·특수문자를 걷어내고 소문자로. 표기 차이를 가려내기 위한 최소한의 정규화다. */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-_()\\[\\]{}.,'\"·&/]", "").toLowerCase();
    }
}
