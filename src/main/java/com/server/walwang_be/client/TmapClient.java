package com.server.walwang_be.client;

import com.server.walwang_be.config.TmapProperties;
import com.server.walwang_be.service.GeoDistance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 티맵 보행자 경로안내.
 *
 * 코스는 지점 여러 개를 잇는 것이라 구간(1→2, 2→3, ...)마다 한 번씩 호출한다.
 * 구간끼리는 서로의 결과를 필요로 하지 않아 동시에 부른다 — 순차로 부르면
 * 구간 수만큼 대기가 쌓여, 지점 4개짜리 코스가 그것만으로 1.5초를 넘긴다.
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
    private final Executor executor;

    /** 이보다 가까우면 같은 자리로 본다. 좌표 정밀도 차이만큼의 여유다. */
    private static final double SAME_SPOT_METERS = 5;
    /** 부르지 않고 건너뛴 구간. 이을 점도, 더할 거리도 없다. */
    private static final Leg EMPTY_LEG = new Leg(List.of(), 0, 0);

    public TmapClient(TmapProperties properties, ObjectMapper objectMapper,
                      @Qualifier("tmapExecutor") Executor executor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;

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

        long startedAt = System.currentTimeMillis();
        int calls = 0;

        // 구간을 한꺼번에 띄운다. 결과는 인덱스 순서 그대로 받아 이어 붙인다 —
        // 먼저 끝난 순서로 담으면 경로선이 뒤엉킨다.
        List<CompletableFuture<Optional<Leg>>> pending = new ArrayList<>(waypoints.size() - 1);
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            Waypoint from = waypoints.get(i);
            Waypoint to = waypoints.get(i + 1);

            if (sameSpot(from, to)) {
                // 같은 건물에 든 가게라 좌표가 같다. 티맵은 길이가 0인 구간을 400으로 거절하고,
                // 그 하나 때문에 코스 전체 경로선이 사라진다. 걸을 거리가 없으니 부르지 않는다.
                pending.add(CompletableFuture.completedFuture(Optional.of(EMPTY_LEG)));
                continue;
            }
            calls++;
            pending.add(CompletableFuture.supplyAsync(() -> requestLeg(from, to), executor));
        }

        List<Optional<Leg>> legs = awaitAll(pending);
        if (legs == null) {
            return Optional.empty();
        }

        List<List<Double>> path = new ArrayList<>();
        int totalDistanceMeters = 0;
        int totalTimeSeconds = 0;

        for (int i = 0; i < legs.size(); i++) {
            Optional<Leg> leg = legs.get(i);
            if (leg.isEmpty()) {
                log.warn("티맵 경로 조회 실패로 경로선을 생략합니다 (구간 {}/{}, 호출 {}회)",
                        i + 1, pending.size(), calls);
                return Optional.empty();
            }
            Leg found = leg.get();
            appendPath(path, found.path());
            totalDistanceMeters += found.distanceMeters();
            totalTimeSeconds += found.timeSeconds();
        }

        log.info("티맵 경로 조회: 지점 {}개 -> 구간 {}개, 호출 {}회 (거리 {}m, 도보 {}초, {}ms)",
                waypoints.size(), pending.size(), calls, totalDistanceMeters, totalTimeSeconds,
                System.currentTimeMillis() - startedAt);

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

    /**
     * 모든 구간이 끝나기를 전체 제한시간 안에서 기다린다.
     *
     * 구간마다 읽기 제한시간이 따로 걸려 있지만 그건 한 건 기준이라,
     * 스레드가 모자라 뒤에서 대기하는 구간까지 더하면 전체가 그보다 오래 끌 수 있다.
     * 화면이 기다리는 시간은 여기서 한 번 더 잘라 둔다.
     *
     * @return 제한시간을 넘기거나 끊기면 null — 호출한 쪽은 경로선 없이 진행한다
     */
    private List<Optional<Leg>> awaitAll(List<CompletableFuture<Optional<Leg>>> pending) {
        long budgetMillis = properties.timeoutOrDefault().toMillis();
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(budgetMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("티맵 경로 조회가 {}ms를 넘겨 경로선을 생략합니다 (구간 {}개)",
                    budgetMillis, pending.size());
            // 아직 도는 건 결과를 쓰지 않는다. 이미 나간 요청은 각자 제한시간에 끝난다.
            pending.forEach(future -> future.cancel(true));
            return null;
        } catch (ExecutionException e) {
            log.warn("티맵 경로 조회 중 오류로 경로선을 생략합니다: {}", e.getCause());
            pending.forEach(future -> future.cancel(true));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.forEach(future -> future.cancel(true));
            return null;
        }
        return pending.stream().map(CompletableFuture::join).toList();
    }

    /** 두 지점이 사실상 같은 자리인지. 한 건물에 여러 가게가 든 경우가 많다. */
    private boolean sameSpot(Waypoint from, Waypoint to) {
        return GeoDistance.between(from.lat(), from.lng(), to.lat(), to.lng()) < SAME_SPOT_METERS;
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
