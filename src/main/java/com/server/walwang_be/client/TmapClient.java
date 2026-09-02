package com.server.walwang_be.client;

import com.server.walwang_be.config.TmapProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 티맵 보행자 경로안내.
 *
 * 코스는 지점 여러 개를 잇는 것이라 구간(1→2, 2→3, ...)마다 한 번씩 호출한다.
 * 지점이 n개면 호출은 n-1회다 — 무료 요금제가 하루 1,000건이라
 * 실제로 몇 번 나갔는지 로그로 남긴다.
 *
 * 응답은 GeoJSON FeatureCollection이다:
 *   features[0] (Point) 의 properties 에만 totalDistance / totalTime 이 있고,
 *   LineString feature 들의 coordinates 를 이어 붙이면 경로선이 된다.
 *   좌표 순서는 [경도, 위도] 로, 우리 walkPath([위도, 경도])와 반대다.
 *
 * appKey는 어떤 경로로도 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class TmapClient {

    private final TmapProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TmapClient(TmapProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeoutOrDefault().toMillis());
        factory.setReadTimeout((int) properties.timeoutOrDefault().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrlOrDefault())
                .requestFactory(factory)
                .build();
    }

    /**
     * 지점들을 차례로 이어 걷는 경로.
     *
     * 한 구간이라도 실패하면 전체를 포기한다 — 일부만 그린 경로선은
     * 실제와 다른 길을 보여주게 되어 없느니만 못하다.
     *
     * @param waypoints 방문 순서대로의 지점. 2개 미만이면 그릴 경로가 없다
     * @return 실패하면 비어 있음. 호출한 쪽은 경로선 없이 코스를 그대로 내보낸다
     */
    public Optional<WalkRoute> walkRoute(List<Waypoint> waypoints) {
        if (!properties.configured()) {
            log.warn("tmap.app-key가 설정되지 않아 경로선을 생략합니다.");
            return Optional.empty();
        }
        if (waypoints.size() < 2) {
            return Optional.empty();
        }

        List<List<Double>> path = new ArrayList<>();
        int totalDistanceMeters = 0;
        int totalTimeSeconds = 0;
        int calls = 0;

        for (int i = 0; i + 1 < waypoints.size(); i++) {
            Waypoint from = waypoints.get(i);
            Waypoint to = waypoints.get(i + 1);

            Optional<Leg> leg = requestLeg(from, to);
            calls++;
            if (leg.isEmpty()) {
                log.warn("티맵 경로 조회 실패로 경로선을 생략합니다 (구간 {}/{}, 호출 {}회)",
                        i + 1, waypoints.size() - 1, calls);
                return Optional.empty();
            }
            Leg found = leg.get();
            appendPath(path, found.path());
            totalDistanceMeters += found.distanceMeters();
            totalTimeSeconds += found.timeSeconds();
        }

        log.info("티맵 경로 조회: 지점 {}개 -> 구간 {}개, 호출 {}회 (거리 {}m, 도보 {}초)",
                waypoints.size(), waypoints.size() - 1, calls, totalDistanceMeters, totalTimeSeconds);

        return Optional.of(new WalkRoute(path, totalDistanceMeters, totalTimeSeconds, calls));
    }

    private Optional<Leg> requestLeg(Waypoint from, Waypoint to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startX", from.lng());
        body.put("startY", from.lat());
        body.put("endX", to.lng());
        body.put("endY", to.lat());
        body.put("startName", from.name());
        body.put("endName", to.name());
        // WGS84GEO: 위경도 그대로 주고받는다.
        body.put("reqCoordType", "WGS84GEO");
        body.put("resCoordType", "WGS84GEO");
        // 0: 추천. 최단/최소보행자도로 같은 다른 값은 지금 쓰지 않는다.
        body.put("searchOption", "0");

        String raw;
        try {
            raw = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/tmap/routes/pedestrian")
                            .queryParam("version", 1)
                            .build())
                    .header("appKey", properties.appKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 예외 메시지에 URL이나 헤더가 섞이지 않도록 종류만 남긴다.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.warn("티맵 호출 실패: {}", root.getClass().getSimpleName());
            return Optional.empty();
        }

        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return parse(raw);
    }

    /**
     * FeatureCollection에서 경로선과 합계를 뽑는다.
     *
     * totalDistance/totalTime은 첫 Point feature에만 있고,
     * 나머지 LineString feature들이 실제 선분이다.
     */
    private Optional<Leg> parse(String raw) {
        JsonNode features;
        try {
            features = objectMapper.readTree(raw).path("features");
        } catch (RuntimeException e) {
            log.warn("티맵 응답을 읽지 못했습니다: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (!features.isArray() || features.isEmpty()) {
            return Optional.empty();
        }

        int distance = 0;
        int time = 0;
        List<List<Double>> path = new ArrayList<>();

        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            if (properties.has("totalDistance")) {
                distance = properties.path("totalDistance").asInt(0);
                time = properties.path("totalTime").asInt(0);
            }
            JsonNode geometry = feature.path("geometry");
            if (!"LineString".equals(geometry.path("type").asString(""))) {
                continue;
            }
            for (JsonNode point : geometry.path("coordinates")) {
                if (point.isArray() && point.size() >= 2) {
                    // 티맵은 [경도, 위도], 우리는 [위도, 경도]로 뒤집어 담는다.
                    path.add(List.of(point.get(1).asDouble(), point.get(0).asDouble()));
                }
            }
        }
        return path.isEmpty() ? Optional.empty() : Optional.of(new Leg(path, distance, time));
    }

    /** 구간을 이을 때 앞 구간의 끝점과 겹치는 첫 점은 버린다. */
    private void appendPath(List<List<Double>> path, List<List<Double>> leg) {
        if (path.isEmpty()) {
            path.addAll(leg);
            return;
        }
        List<Double> last = path.get(path.size() - 1);
        for (List<Double> point : leg) {
            if (point.equals(last)) {
                continue;
            }
            path.add(point);
        }
    }

    /** 경로에 들를 지점 하나. */
    public record Waypoint(String name, double lat, double lng) {
    }

    /** 구간 하나의 결과. */
    private record Leg(List<List<Double>> path, int distanceMeters, int timeSeconds) {
    }

    /**
     * 전체 경로.
     *
     * @param apiCalls 이번 코스에 나간 티맵 호출 수. 무료 요금제 한도를 가늠하는 값이다
     */
    public record WalkRoute(List<List<Double>> path, int distanceMeters,
                            int timeSeconds, int apiCalls) {
    }
}
