package com.server.walwang_be.service;

import com.server.walwang_be.dto.CourseRecommendRequest;
import com.server.walwang_be.dto.CourseResponse;
import com.server.walwang_be.dto.CourseSaveRequest;
import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.dto.NearbyPlaceResponse;
import com.server.walwang_be.dto.SavedCourseSummaryResponse;
import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.SavedCourse;
import com.server.walwang_be.entity.SavedCourseStore;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.repo.SavedCourseRepository;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.repo.PlaceEmbeddingRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import com.server.walwang_be.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 코스 추천과 저장.
 *
 * 추천은 지금 우리 DB의 가게만 보고 고른다. 나중에는 벡터 기반 추천 모델이
 * 외부 데이터까지 섞어 후보를 내려줄 예정이라, 이 클래스에서 바뀌는 건
 * recommend()가 후보를 얻어오는 부분 하나다 — 응답을 만드는 코드는 그대로 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    /** 성인 도보 속도(m/분). 4.2km/h 정도로 잡는다. */
    private static final double WALK_SPEED_METERS_PER_MINUTE = 70.0;
    /** 한 곳에 머무는 시간(분). 카페든 공원이든 일단 같게 본다. */
    private static final int DWELL_MINUTES_PER_STORE = 30;

    private final StoreRepository storeRepository;
    private final StoreSizeStatusRepository storeSizeStatusRepository;
    private final StoreTagCountRepository storeTagCountRepository;
    private final SavedCourseRepository savedCourseRepository;
    private final UserRepository userRepository;
    private final PlaceEmbeddingRepository placeEmbeddingRepository;
    private final TmapClient tmapClient;
    private final NearbyPlaceFinder nearbyPlaceFinder;
    private final TourVerifier tourVerifier;
    private final StoreCandidates storeCandidates;

    /**
     * 코스 추천.
     *
     * 조건에 맞는 곳이 모자라면 기준을 단계적으로 낮춘다 — 빈 코스를 주느니
     * 조금 어긋나더라도 갈 만한 곳을 주고 relaxed로 알리는 편이 낫다.
     */
    public CourseResponse recommend(CourseRecommendRequest request, boolean demo) {
        CourseDuration duration = request.duration();
        List<Store> nearby = storeRepository.findWithinRadius(
                request.lat(), request.lng(), duration.searchRadius());

        // 공사 목록과 리뷰 점수, 두 갈래의 근거를 모아 후보를 고른다.
        Set<UUID> verified = tourVerifier.verifiedAmong(nearby);
        Map<UUID, StoreSizeStatus> statusByStore = storeCandidates.statuses(nearby, request.dogSize());

        List<Store> pool = nearby.stream()
                .filter(store -> storeCandidates.hasEvidence(store, verified, statusByStore))
                .toList();
        List<Store> candidates = pool.stream()
                .filter(store -> matchesPurpose(store, request.purposes()))
                .toList();
        boolean relaxed = false;

        if (candidates.size() < duration.maxStores() && pool.size() > candidates.size()) {
            // 목적에 맞는 곳이 모자라면 목적만 내려놓는다.
            // 근거 조건(hasEvidence)은 어떤 경우에도 풀지 않는다 —
            // 추천해도 되는 곳인지를 가르는 기준이라 자리를 채우려고 양보할 수 없다.
            candidates = pool;
            relaxed = true;
        }

        candidates = rankCandidates(candidates, demo);
        List<Store> route = buildRoute(candidates, request.lat(), request.lng(), duration.maxStores());
        Walk walk = walk(route, request.lat(), request.lng());

        return new CourseResponse(
                null,
                null,
                request.dogSize(),
                request.purposes(),
                duration,
                walk.distanceMeters(),
                walk.durationMinutes(),
                walk.path(),
                null,
                relaxed,
                storeCandidates.toResponses(route, statusByStore, storeCandidates.tags(route), demo),
                nearbyPlaceFinder.around(route));
    }

    @Transactional
    public UUID save(UUID userId, CourseSaveRequest request) {
        List<Store> stores = loadStoresInOrder(request.storeIds());

        SavedCourse course = SavedCourse.builder()
                // 저장만 할 것이라 User를 실제로 읽어올 필요가 없다.
                .user(userRepository.getReferenceById(userId))
                .title(request.title())
                .dogSize(request.dogSize())
                .purposes(request.purposes())
                .duration(request.duration())
                .description(request.description())
                .build();

        for (int i = 0; i < stores.size(); i++) {
            course.addStore(stores.get(i), i + 1);
        }
        return savedCourseRepository.save(course).getCourseId();
    }

    /** 저장된 코스 조회. 남의 코스는 존재를 알려주지 않고 403으로 끊는다. */
    public CourseResponse findOne(UUID userId, UUID courseId, boolean demo) {
        SavedCourse course = loadOwnedCourse(userId, courseId);

        List<Store> stores = course.getStores().stream()
                .map(SavedCourseStore::getStore)
                .toList();
        Map<UUID, StoreSizeStatus> statusByStore = storeCandidates.statuses(stores, course.getDogSize());

        // 경로·거리·시간은 저장하지 않으므로 조회할 때마다 다시 얻는다.
        Walk walk = walk(stores);

        return new CourseResponse(
                course.getCourseId(),
                course.getTitle(),
                course.getDogSize(),
                List.copyOf(course.getPurposes()),
                course.getDuration(),
                walk.distanceMeters(),
                walk.durationMinutes(),
                walk.path(),
                course.getDescription(),
                // 저장된 코스는 이미 확정된 결과라 완화 여부를 따지지 않는다.
                false,
                storeCandidates.toResponses(stores, statusByStore, storeCandidates.tags(stores), demo),
                nearbyPlaceFinder.around(stores));
    }

    /** 저장한 코스 목록. 최근에 저장한 것부터. */
    public List<SavedCourseSummaryResponse> findMyCourses(UUID userId, boolean demo) {
        return savedCourseRepository.findByUser_UserIdOrderByCreatedAtDesc(userId).stream()
                .map(course -> {
                    List<Store> stores = course.getStores().stream()
                            .map(SavedCourseStore::getStore)
                            .toList();
                    // 목록은 직선 추정치로 채운다. 코스마다 티맵을 부르면
                    // 호출이 코스 수만큼 늘어나고, 미리보기에는 이 정도면 충분하다.
                    double distance = straightDistance(stores);
                    return SavedCourseSummaryResponse.of(course, demo,
                            (int) Math.round(distance),
                            totalDuration(distance, stores.size()));
                })
                .toList();
    }

    /** 제목만 바꾼다. 남의 코스면 403, 없으면 404. */
    @Transactional
    public void rename(UUID userId, UUID courseId, String title) {
        loadOwnedCourse(userId, courseId).rename(title);
    }

    @Transactional
    public void delete(UUID userId, UUID courseId) {
        savedCourseRepository.delete(loadOwnedCourse(userId, courseId));
    }

    private SavedCourse loadOwnedCourse(UUID userId, UUID courseId) {
        SavedCourse course = savedCourseRepository.findWithStoresByCourseId(courseId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 코스입니다."));

        if (!course.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("자신이 저장한 코스만 볼 수 있습니다.");
        }
        return course;
    }

    /**
     * 요청한 순서를 그대로 지킨다 — 순서가 곧 방문 순서라 정렬이 바뀌면 코스가 달라진다.
     * findAllById는 순서를 보장하지 않으므로 한 번 읽어 맵으로 되짚는다.
     */
    private List<Store> loadStoresInOrder(List<UUID> storeIds) {
        Map<UUID, Store> found = storeRepository.findAllById(storeIds).stream()
                .collect(Collectors.toMap(Store::getStoreId, store -> store));

        List<Store> ordered = new ArrayList<>(storeIds.size());
        for (UUID storeId : storeIds) {
            Store store = found.get(storeId);
            if (store == null) {
                throw new NoSuchElementException("존재하지 않는 가게입니다: " + storeId);
            }
            ordered.add(store);
        }
        return ordered;
    }

    /**
     * 후보 순서를 정한다.
     *
     * 임베딩이 있으면 벡터 유사도로 줄을 세우고, 없으면 그대로 둬서 거리순 경로 구성에 맡긴다.
     * 실제 모드에는 리뷰가 없어 임베딩도 없는데, 그건 오류가 아니라 정상 경로다 —
     * 시연이든 아니든 같은 코드가 돌고 파라미터만 달라진다.
     *
     * 기준 벡터(queryEmbedding)는 아직 AI 쪽에서 오지 않아 지금은 늘 폴백을 탄다.
     * 연결되면 이 메서드 안에서만 바뀐다.
     */
    private List<Store> rankCandidates(List<Store> candidates, boolean demo) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<UUID> ids = candidates.stream().map(Store::getStoreId).toList();
        if (!placeEmbeddingRepository.existsAny(ids, demo)) {
            // 임베딩이 하나도 없다 -> 거리순으로 간다.
            return candidates;
        }
        List<UUID> ranked = placeEmbeddingRepository.rankBySimilarity(queryEmbedding(), ids, demo);
        if (ranked.isEmpty()) {
            return candidates;
        }
        Map<UUID, Store> byId = candidates.stream()
                .collect(Collectors.toMap(Store::getStoreId, store -> store));

        List<Store> ordered = new ArrayList<>(candidates.size());
        ranked.forEach(id -> {
            Store store = byId.remove(id);
            if (store != null) {
                ordered.add(store);
            }
        });
        // 임베딩이 없는 후보는 뒤에 붙인다. 버리면 코스가 짧아진다.
        ordered.addAll(byId.values());
        return ordered;
    }

    /**
     * 기준 벡터. AI 추천 모델이 붙기 전까지는 없다.
     * null 을 돌려주면 rankBySimilarity 가 빈 목록을 주고 거리순으로 폴백된다.
     */
    private float[] queryEmbedding() {
        return null;
    }

    /**
     * 가까운 곳부터 차례로 이어 붙인다(최근접 이웃).
     *
     * 최적 경로는 아니지만, 지도 중심에서 출발해 계속 가장 가까운 다음 지점으로 가므로
     * 왔다 갔다 하는 동선은 나오지 않는다. 길찾기 API가 붙으면 이 자리를 대체한다.
     */
    private List<Store> buildRoute(List<Store> candidates, double lat, double lng, int maxStores) {
        List<Store> remaining = new ArrayList<>(candidates);
        List<Store> route = new ArrayList<>(Math.min(maxStores, remaining.size()));

        double currentLat = lat;
        double currentLng = lng;

        while (!remaining.isEmpty() && route.size() < maxStores) {
            final double fromLat = currentLat;
            final double fromLng = currentLng;

            Store nearest = remaining.stream()
                    .min(Comparator.comparingDouble(store ->
                            GeoDistance.between(fromLat, fromLng, store.getLat(), store.getLng())))
                    .orElseThrow();

            route.add(nearest);
            remaining.remove(nearest);
            currentLat = nearest.getLat();
            currentLng = nearest.getLng();
        }
        return route;
    }

    /**
     * 실제 보행 경로. 티맵이 알려준 길을 쓰고, 못 받으면 직선으로 대신한다.
     *
     * 실패해도 코스 자체는 그대로 내보낸다 — 경로선이 안 그려질 뿐이고,
     * 어디를 갈지는 이미 정해져 있기 때문이다.
     */
    private Walk walk(List<Store> route, double lat, double lng) {
        if (route.isEmpty()) {
            return new Walk(List.of(), 0, 0);
        }
        List<TmapClient.Waypoint> waypoints = new ArrayList<>(route.size() + 1);
        waypoints.add(new TmapClient.Waypoint("출발지", lat, lng));
        route.forEach(store -> waypoints.add(
                new TmapClient.Waypoint(store.getName(), store.getLat(), store.getLng())));
        return walk(route, waypoints, lat, lng);
    }

    /**
     * 저장된 코스의 경로. 출발지는 첫 가게로 삼는다 —
     * 지도 중심이 어디였는지는 저장하지 않기 때문이다.
     */
    private Walk walk(List<Store> route) {
        if (route.size() < 2) {
            return new Walk(List.of(), 0, route.size() * DWELL_MINUTES_PER_STORE);
        }
        List<TmapClient.Waypoint> waypoints = route.stream()
                .map(store -> new TmapClient.Waypoint(
                        store.getName(), store.getLat(), store.getLng()))
                .toList();
        Store first = route.get(0);
        return walk(route, waypoints, first.getLat(), first.getLng());
    }

    private Walk walk(List<Store> route, List<TmapClient.Waypoint> waypoints,
                      double lat, double lng) {

        Optional<TmapClient.WalkRoute> found = tmapClient.walkRoute(waypoints);
        if (found.isPresent()) {
            TmapClient.WalkRoute walk = found.get();
            // 티맵이 준 시간은 걷는 시간뿐이라, 머무는 시간을 더해야 코스 소요시간이 된다.
            int minutes = (int) Math.round(walk.timeSeconds() / 60.0)
                    + route.size() * DWELL_MINUTES_PER_STORE;
            return new Walk(walk.path(), walk.distanceMeters(), minutes);
        }
        // 경로선 없이 간다. 거리·시간은 직선 기준 추정으로 채운다.
        double distance = totalDistance(toWalkPath(route, lat, lng));
        return new Walk(List.of(), (int) Math.round(distance),
                totalDuration(distance, route.size()));
    }

    /** 화면에 그릴 경로와 그 합계. */
    private record Walk(List<List<Double>> path, int distanceMeters, int durationMinutes) {
    }

    /** 지도 중심에서 출발해 가게를 차례로 잇는다. [[위도, 경도], ...] */
    private List<List<Double>> toWalkPath(List<Store> route, double lat, double lng) {
        if (route.isEmpty()) {
            return List.of();
        }
        List<List<Double>> path = new ArrayList<>(route.size() + 1);
        path.add(List.of(lat, lng));
        route.forEach(store -> path.add(List.of(store.getLat(), store.getLng())));
        return path;
    }

    private double totalDistance(List<List<Double>> walkPath) {
        double total = 0;
        for (int i = 1; i < walkPath.size(); i++) {
            List<Double> from = walkPath.get(i - 1);
            List<Double> to = walkPath.get(i);
            total += GeoDistance.between(from.get(0), from.get(1), to.get(0), to.get(1));
        }
        return total;
    }

    /** 가게를 차례로 잇는 직선거리. 티맵 없이 우리가 계산하는 값이다. */
    private double straightDistance(List<Store> stores) {
        double total = 0;
        for (int i = 1; i < stores.size(); i++) {
            Store from = stores.get(i - 1);
            Store to = stores.get(i);
            total += GeoDistance.between(from.getLat(), from.getLng(), to.getLat(), to.getLng());
        }
        return total;
    }

    /** 걷는 시간 + 각 가게에 머무는 시간. */
    private int totalDuration(double distanceMeters, int storeCount) {
        return (int) Math.round(distanceMeters / WALK_SPEED_METERS_PER_MINUTE)
                + storeCount * DWELL_MINUTES_PER_STORE;
    }

    private boolean matchesPurpose(Store store, List<CoursePurpose> purposes) {
        return purposes.stream().anyMatch(purpose -> purpose.matches(store.getType()));
    }

    /** 리뷰가 없으면 status가 UNKNOWN이라 여기서 걸러지지 않는다 — 가보지 않은 곳도 후보다. */
    private boolean notImpossible(StoreSizeStatus status) {
        return status == null || status.status() != AvailabilityStatus.IMPOSSIBLE;
    }

}
