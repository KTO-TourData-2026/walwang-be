package com.server.walwang_be.service;

import com.server.walwang_be.client.TourApiClient;
import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 지도에 올릴 가게.
 *
 * 공사 데이터와 매칭된 곳만 보여준다 — 반려동물 동반이 확인된 곳이라는 뜻이라,
 * 전체 7천여 건을 흩뿌리는 것보다 지도가 훨씬 읽힌다.
 *
 * 매칭은 요청마다 새로 돈다. 공사 응답은 메모리에서 쓰고 버리며 저장하지 않는다 —
 * 저장하면 우리 쪽에 사본이 생겨 갱신 책임이 따라붙는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MapStoreService {

    /** 성동구. 지금은 이 지역만 서비스한다. */
    private static final String SEOUL = "11";
    private static final String SEONGDONG = "200";
    /** 성동구 전체가 102건이라 한 번에 받는다. */
    private static final int PAGE_SIZE = 500;

    private final TourApiClient tourApiClient;
    private final TourStoreMatcher matcher;
    private final StoreRepository storeRepository;
    private final StoreService storeService;
    private final StoreSizeStatusRepository storeSizeStatusRepository;

    /**
     * 지도 핀 조회.
     *
     * 공사 API가 죽어 있으면 빈 목록을 준다. 지도가 아예 안 뜨는 것보다 낫고,
     * 사용자가 다시 움직이면 재시도되기 때문이다.
     */
    public List<StoreCardResponse> findVerifiedNearby(double lat, double lng, int radius,
                                                     DogSize size, boolean demo) {
        List<TourStoreMatcher.TourPoint> points = fetchTourPoints();
        if (points.isEmpty()) {
            return List.of();
        }

        // 후보를 한 번만 읽어 매칭에 재사용한다. 지점마다 반경 쿼리를 던지면
        // 항목 수 × 단계 수만큼 왕복이 생겨 원격 DB에서는 그것만으로 몇 초가 걸린다.
        long startedAt = System.currentTimeMillis();
        List<Store> pool = storeRepository.findWithinRadius(lat, lng, poolRadius(radius));
        long loadedAt = System.currentTimeMillis();

        Set<UUID> verified = new LinkedHashSet<>(
                matcher.matchAll(points, pool).matched().values().stream()
                        .map(TourStoreMatcher.Match::storeId)
                        .toList());
        log.info("지도 매칭: 공사 {}건 x 후보 {}건 -> {}건 (조회 {}ms, 매칭 {}ms)",
                points.size(), pool.size(), verified.size(),
                loadedAt - startedAt, System.currentTimeMillis() - loadedAt);

        if (verified.isEmpty()) {
            return List.of();
        }
        // 지도는 화면 안만 보여준다. 매칭에 쓴 후보 중 요청 반경 안의 것만 남긴다.
        List<Store> inView = pool.stream()
                .filter(store -> verified.contains(store.getStoreId()))
                .filter(store -> GeoDistance.between(lat, lng, store.getLat(), store.getLng()) <= radius)
                .toList();

        // 크기별 상태는 StoreService가 한 번에 읽어 붙인다(N+1 방지).
        return storeService.toCards(excludeImpossible(inView, size), size, demo, true);
    }

    /**
     * 그 크기로 동반 불가라고 확인된 곳을 지도에서 뺀다.
     *
     * UNKNOWN은 남긴다 — 아직 아무도 안 가본 곳이라는 뜻이지 못 간다는 뜻이 아니다.
     * 그래서 리뷰가 쌓이기 전에는 아무것도 걸러지지 않는 것이 정상이다.
     */
    private List<Store> excludeImpossible(List<Store> stores, DogSize size) {
        if (size == null || stores.isEmpty()) {
            return stores;
        }
        Map<UUID, StoreSizeStatus> statusByStore = storeSizeStatusRepository
                .findByStore_StoreIdIn(stores.stream().map(Store::getStoreId).toList()).stream()
                .filter(status -> status.getSize() == size)
                .collect(Collectors.toMap(
                        status -> status.getStore().getStoreId(), status -> status));

        return stores.stream()
                .filter(store -> {
                    StoreSizeStatus status = statusByStore.get(store.getStoreId());
                    return status == null || status.status() != AvailabilityStatus.IMPOSSIBLE;
                })
                .toList();
    }

    /**
     * 후보를 읽을 반경.
     *
     * 화면 밖 공사 지점도 매칭에 쓰이려면 요청 반경보다 넓게 읽어야 한다 —
     * 매칭 규칙이 최대 300m까지 보기 때문이다.
     */
    private int poolRadius(int radius) {
        return radius + (int) TourStoreMatcher.WIDE_RADIUS_METERS;
    }

    /**
     * 공사 데이터를 좌표 목록으로 바꾼다.
     * 실패는 삼키고 빈 목록으로 돌린다 — 지도는 떠야 한다.
     */
    private List<TourStoreMatcher.TourPoint> fetchTourPoints() {
        TourApiClient.TourResponse response;
        try {
            response = tourApiClient.areaBasedList(SEOUL, SEONGDONG, null, PAGE_SIZE, 1, true);
        } catch (RuntimeException e) {
            log.warn("공사 API 조회 실패, 지도를 빈 목록으로 응답합니다: {}", e.getMessage());
            return List.of();
        }

        List<TourStoreMatcher.TourPoint> points = new ArrayList<>(response.items().size());
        for (Map<String, Object> item : response.items()) {
            Double itemLat = toDouble(item.get("mapy"));
            Double itemLng = toDouble(item.get("mapx"));
            if (itemLat != null && itemLng != null) {
                points.add(new TourStoreMatcher.TourPoint(
                        String.valueOf(item.get("title")), itemLat, itemLng));
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
