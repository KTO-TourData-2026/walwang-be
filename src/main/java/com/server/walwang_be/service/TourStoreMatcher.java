package com.server.walwang_be.service;

import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 관광공사 반려동물 동반여행 데이터를 우리 stores와 맞춘다.
 *
 * 이름만으로는 맞출 수 없다 — 관광공사는 브랜드+지점("올리브영 서울숲역점"),
 * 소상공인 데이터는 법인명("씨제이올리브영서울숲역점") 체계라 표기 규칙이 다르다.
 * 그렇다고 좌표 최근접만 쓰면 더 나쁘다 — 성수동은 한 건물에 편집샵이 여러 개라
 * 0.1m 거리에서도 엉뚱한 가게가 잡힌다.
 *
 * 그래서 좌표로 후보를 좁힌 뒤 이름으로 고른다. 확신이 큰 규칙부터 차례로 적용해,
 * 앞 단계에서 붙은 것은 뒤 단계에서 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TourStoreMatcher {

    /** 이름 근거가 강한 규칙(완전일치·접두어)에 쓰는 반경. */
    public static final double WIDE_RADIUS_METERS = 300;
    /** 유사도처럼 근거가 약한 규칙에 쓰는 반경. */
    public static final double TIGHT_RADIUS_METERS = 30;
    /** 이 길이 미만으로 겹치면 우연일 수 있어 거리까지 본다. */
    public static final int SHORT_NAME_LENGTH = 4;
    /** 유사도로 붙일 때의 최소 기준. */
    public static final double SIMILARITY_THRESHOLD = 0.6;

    private final StoreRepository storeRepository;

    /**
     * 3단계로 나눠 맞춘다.
     *
     * 후보는 반경만큼 한 번만 읽고 단계별로 재사용한다.
     * 마지막에 한 가게에 둘 이상 붙은 건 전부 버린다 — 어느 한쪽은 반드시 틀렸고,
     * 어느 쪽이 맞는지 가릴 근거가 없기 때문이다.
     */
    /**
     * 후보를 미리 받아 메모리에서만 맞춘다.
     *
     * 지점마다 반경 쿼리를 던지면 항목 수 × 단계 수만큼 왕복이 생긴다 —
     * 성동구 102건이면 268회라, 원격 DB에서는 그것만으로 5초가 넘는다.
     * 후보를 한 번 읽어두면 거리 계산은 메모리에서 끝난다.
     */
    public StagedResult matchAll(List<TourPoint> points, List<Store> pool) {
        Map<String, Match> matched = new LinkedHashMap<>();
        List<Stage> stages = new ArrayList<>();

        List<TourPoint> remaining = new ArrayList<>(points);
        remaining = runInMemory(stages, "1. 완전 일치 (300m)", remaining, matched,
                pool, WIDE_RADIUS_METERS, this::byExact);
        remaining = runInMemory(stages, "2. 접두어 규칙 (300m)", remaining, matched,
                pool, WIDE_RADIUS_METERS, this::byPrefix);
        remaining = runInMemory(stages, "3. 유사도 >= 0.6 (30m, 치환+지점명 제거)",
                remaining, matched, pool, TIGHT_RADIUS_METERS, this::bySimilarity);

        return dropDuplicates(points.size(), stages, matched, remaining);
    }

    private List<TourPoint> runInMemory(List<Stage> stages, String name,
                                        List<TourPoint> targets,
                                        Map<String, Match> matched,
                                        List<Store> pool, double radius, Rule rule) {
        List<Pair> hits = new ArrayList<>();
        List<TourPoint> leftover = new ArrayList<>();

        for (TourPoint point : targets) {
            List<StoreRepository.NearbyStore> candidates = nearby(pool, point, radius);
            Optional<Scored> found = rule.apply(point.title(), candidates);
            if (found.isEmpty()) {
                leftover.add(point);
                continue;
            }
            Scored scored = found.get();
            Match match = new Match(scored.store().getStoreId(), scored.store().getExternalId(),
                    scored.store().getName(), scored.store().getAddress(),
                    scored.store().getDistance(), candidates.size(), name, scored.similarity());

            matched.put(point.title(), match);
            hits.add(new Pair(point.title(), match));
        }
        stages.add(new Stage(name, hits.size(), matched.size(), hits));
        return leftover;
    }

    /** 반경 안의 후보를 가까운 순으로. 쿼리와 같은 순서를 유지해야 규칙이 똑같이 돈다. */
    private List<StoreRepository.NearbyStore> nearby(List<Store> pool, TourPoint point, double radius) {
        List<StoreRepository.NearbyStore> found = new ArrayList<>();
        for (Store store : pool) {
            double distance = GeoDistance.between(
                    point.lat(), point.lng(), store.getLat(), store.getLng());
            if (distance <= radius) {
                found.add(new InMemoryStore(store.getStoreId(), store.getExternalId(),
                        store.getName(), store.getAddress(), distance));
            }
        }
        found.sort(Comparator.comparingDouble(StoreRepository.NearbyStore::getDistance));
        return found;
    }

    /** 메모리에서 만든 후보. 쿼리 결과와 같은 모양이라 규칙을 그대로 쓴다. */
    private record InMemoryStore(UUID storeId, String externalId, String name,
                                 String address, double distance)
            implements StoreRepository.NearbyStore {
        @Override public UUID getStoreId() { return storeId; }
        @Override public String getExternalId() { return externalId; }
        @Override public String getName() { return name; }
        @Override public String getAddress() { return address; }
        @Override public double getDistance() { return distance; }
    }

    public StagedResult matchAll(List<TourPoint> points) {
        Map<String, Match> matched = new LinkedHashMap<>();
        List<Stage> stages = new ArrayList<>();

        List<TourPoint> remaining = new ArrayList<>(points);
        remaining = runStage(stages, "1. 완전 일치 (300m)", remaining, matched,
                WIDE_RADIUS_METERS, this::byExact);
        remaining = runStage(stages, "2. 접두어 규칙 (300m)", remaining, matched,
                WIDE_RADIUS_METERS, this::byPrefix);
        remaining = runStage(stages, "3. 유사도 >= 0.6 (30m, 치환+지점명 제거)",
                remaining, matched, TIGHT_RADIUS_METERS, this::bySimilarity);

        return dropDuplicates(points.size(), stages, matched, remaining);
    }

    private List<TourPoint> runStage(List<Stage> stages, String name,
                                     List<TourPoint> targets,
                                     Map<String, Match> matched,
                                     double radius,
                                     Rule rule) {
        List<Pair> hits = new ArrayList<>();
        List<TourPoint> leftover = new ArrayList<>();

        for (TourPoint point : targets) {
            List<StoreRepository.NearbyStore> candidates =
                    storeRepository.findNearbyOrderByDistance(
                            point.lat(), point.lng(), radius);

            Optional<Scored> found = rule.apply(point.title(), candidates);
            if (found.isEmpty()) {
                leftover.add(point);
                continue;
            }
            Scored scored = found.get();
            Match match = new Match(scored.store().getStoreId(), scored.store().getExternalId(),
                    scored.store().getName(), scored.store().getAddress(),
                    scored.store().getDistance(), candidates.size(), name, scored.similarity());

            matched.put(point.title(), match);
            hits.add(new Pair(point.title(), match));
        }
        stages.add(new Stage(name, hits.size(), matched.size(), hits));
        return leftover;
    }

    /**
     * 한 가게에 공사 항목이 둘 이상 붙었으면 그 쌍을 전부 버린다.
     * 하나만 남기면 절반은 틀린 채로 "확인됨" 표시가 붙는다.
     */
    private StagedResult dropDuplicates(int total, List<Stage> stages,
                                        Map<String, Match> matched,
                                        List<TourPoint> unmatched) {
        Map<UUID, List<String>> byStore = new LinkedHashMap<>();
        matched.forEach((title, match) ->
                byStore.computeIfAbsent(match.storeId(), key -> new ArrayList<>()).add(title));

        Map<String, List<String>> dropped = new LinkedHashMap<>();
        byStore.forEach((storeId, titles) -> {
            if (titles.size() > 1) {
                dropped.put(matched.get(titles.get(0)).storeName(), List.copyOf(titles));
            }
        });
        dropped.values().forEach(titles -> titles.forEach(matched::remove));

        return new StagedResult(total, stages, matched, unmatched, dropped);
    }

    /** 정규화 후 글자가 똑같으면 같은 곳으로 본다. 근거가 가장 강해 반경을 넓게 준다. */
    private Optional<Scored> byExact(String title, List<StoreRepository.NearbyStore> candidates) {
        String target = normalize(title);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        // 이미 거리순이라 같은 이름이 여럿이면 가장 가까운 것이 잡힌다.
        return candidates.stream()
                .filter(candidate -> normalize(candidate.getName()).equals(target))
                .findFirst()
                .map(store -> new Scored(store, 1.0));
    }

    /**
     * 한쪽이 다른 쪽으로 시작하면 같은 곳으로 본다.
     * "룩캐스트 성수점" ↔ "룩캐스트"처럼 지점명이 붙고 빠지는 차이를 잡는다.
     * 여러 개가 걸리면 가장 긴 것을 고른다 — 더 많이 겹칠수록 우연일 가능성이 낮다.
     */
    private Optional<Scored> byPrefix(String title, List<StoreRepository.NearbyStore> candidates) {
        String target = normalize(title);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> {
                    String name = normalize(candidate.getName());
                    if (name.isEmpty()) {
                        return false;
                    }
                    if (!target.startsWith(name) && !name.startsWith(target)) {
                        return false;
                    }
                    // 겹치는 글자가 짧으면 우연일 수 있다. "성수연방"이 "성수"로 시작한다고
                    // 같은 곳은 아니다 — 그럴 땐 바로 옆에 있을 때만 인정한다.
                    int overlap = Math.min(target.length(), name.length());
                    return overlap >= SHORT_NAME_LENGTH
                            || candidate.getDistance() <= TIGHT_RADIUS_METERS;
                })
                .max(Comparator.<StoreRepository.NearbyStore>comparingInt(
                                candidate -> normalize(candidate.getName()).length())
                        .thenComparing(StoreRepository.NearbyStore::getDistance,
                                Comparator.reverseOrder()))
                .map(store -> new Scored(store, 1.0));
    }

    /**
     * 지점명을 떼고 비교한다.
     *
     * "탑텐 왕십리역점"과 "캡텐왕십리지점"은 지점명이 길어서 유사도가 부풀려졌다.
     * 브랜드명만 남기면 그런 우연한 겹침이 사라진다.
     */
    private Optional<Scored> bySimilarity(String title, List<StoreRepository.NearbyStore> candidates) {
        String strippedTitle = forCompare(title);

        return candidates.stream()
                .map(candidate -> new Scored(candidate,
                        StoreNameMatcher.similarity(forCompare(candidate.getName()), strippedTitle)))
                .filter(scored -> scored.similarity() >= SIMILARITY_THRESHOLD)
                // 점수가 같으면 가까운 쪽이 맞을 확률이 높다.
                // 동점을 그냥 두면 어느 쪽이 뽑힐지 실행마다 흔들린다.
                .max(Comparator.comparingDouble(Scored::similarity)
                        .thenComparing(scored -> scored.store().getDistance(),
                                Comparator.reverseOrder()));
    }

    /**
     * 유사도 비교용 문자열. 지역명까지 걷어낸다 —
     * 성수동 가게끼리는 "성수"가 공통이라 그것만으로 유사도가 올라간다.
     *
     * 치환을 먼저 하는 이유는 "성수점"이 "성수"가 되고 나면
     * 지점명 제거가 그 토큰을 지우지 않기 때문이다 — 지역 정보가 살아남는다.
     */
    private static String forCompare(String value) {
        // 지역명은 마지막에 뺀다. 치환·지점명 제거가 끝난 뒤라야
        // "성수점" 같은 형태가 이미 "성수"로 정리돼 한 번에 걸린다.
        return StoreNameNormalizer.stripRegions(
                stripBranch(StoreNameNormalizer.canonicalize(value)));
    }

    /**
     * 끝에 붙은 지점명 토큰을 걷어낸다.
     *
     * 공백으로 나눈 뒤 뒤에서부터 지운다 — "스탠드오일 슈즈 플래그십 스토어"처럼
     * 두 토큰에 걸쳐 있는 경우도 함께 처리된다.
     *
     * 토큰이 하나만 남으면 멈춘다. 우리 DB 이름은 대부분 공백이 없어
     * ("씨제이올리브영서울숲역점") 통째로 사라져 버리기 때문이다.
     */
    static String stripBranch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(List.of(value.trim().split("\\s+")));
        while (tokens.size() > 1 && isBranchToken(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return String.join(" ", tokens);
    }

    private static boolean isBranchToken(String token) {
        return token.endsWith("점")        // ~점 / ~지점 / ~직영점
                || token.endsWith("스토어")
                || token.endsWith("부티크")
                || token.endsWith("매장")
                || token.contains("플래그십")
                || token.contains("플래그쉽");
    }

    /** 공백·특수문자를 걷어내고 소문자로. 모든 비교 전에 거친다. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-_()\\[\\]{}.,'\"·&/]", "").toLowerCase();
    }

    private record Scored(StoreRepository.NearbyStore store, double similarity) {
    }

    /** 맞춰볼 대상 하나. */
    public record TourPoint(String title, double lat, double lng) {
    }

    public record Match(UUID storeId, String externalId, String storeName, String address,
                        double distanceMeters, int candidateCount,
                        String stage, double similarity) {
    }

    public record Pair(String tourTitle, Match match) {
    }

    public record Stage(String name, int newMatches, int cumulative, List<Pair> pairs) {
    }

    /**
     * 전체 결과.
     *
     * @param droppedDuplicates 한 가게에 둘 이상 붙어 버려진 쌍
     */
    public record StagedResult(int total, List<Stage> stages,
                               Map<String, Match> matched, List<TourPoint> unmatched,
                               Map<String, List<String>> droppedDuplicates) {
    }

    @FunctionalInterface
    private interface Rule {
        Optional<Scored> apply(String title, List<StoreRepository.NearbyStore> candidates);
    }
}
