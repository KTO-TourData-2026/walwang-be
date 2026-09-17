package com.server.walwang_be.service;

import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 공사 반려동물 동반 목록에 이름이 오른 가게를 가려낸다.
 *
 * 지도는 이 결과로 걸러 내보내고, 코스·대안은 후보 자격(StoreCandidates.hasEvidence)에 쓰고,
 * 상세는 배지로 보여준다. 네 곳이 같은 판정을 내도록 반경 계산까지 여기 한곳에 둔다(within).
 *
 * 결과는 요청 안에서만 산다. 공사 데이터를 우리가 보관하지 않기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TourVerifier {

    private final TourDataProvider tourDataProvider;
    private final TourStoreMatcher matcher;
    private final StoreRepository storeRepository;

    /**
     * 판정에 쓸 공사 지점의 반경. 반경 안 가게에 붙을 수 있는 지점은
     * 매칭 규칙이 보는 최대 거리(300m)만큼 바깥까지 있을 수 있다.
     */
    public static int matchPointRadius(int radius) {
        return radius + (int) TourStoreMatcher.WIDE_RADIUS_METERS;
    }

    /**
     * 매칭 후보를 읽을 반경. 위 지점들의 진짜 주인은 지점에서 다시 300m 떨어져 있을 수 있다.
     *
     * 반경 안 가게만 후보로 두면 경계 밖 진짜 주인이 빠져, 이름이 비슷한 안쪽 가게가 대신 매칭된다
     * (예: 1.25km 의 "룩캐스트 성수점" 대신 0.95km 의 "룩캐스트" — 접두어 규칙).
     */
    public static int matchPoolRadius(int radius) {
        return radius + 2 * (int) TourStoreMatcher.WIDE_RADIUS_METERS;
    }

    /**
     * 중심에서 radius 안의 가게와, 그중 공사 목록에 오른 가게.
     *
     * 지도·코스·대안·상세가 모두 이것을 쓴다. 이렇게 읽고 좁히면 한 가게의 판정은
     * 중심과 반경에 상관없이 같다 — 그 가게에 붙을 수 있는 지점(300m 안)과 그 지점의
     * 경쟁 후보(다시 300m 안)가 언제나 모두 들어오기 때문이다.
     *
     * 후보는 넓게(radius + 600m) 한 번만 읽고, 돌려주는 가게는 radius 로 다시 좁힌다.
     * 공사 데이터를 못 받으면 verified 가 비어 있을 뿐 가게 목록은 그대로 돌려준다.
     */
    public Scope within(double lat, double lng, int radius) {
        long startedAt = System.currentTimeMillis();
        List<Store> pool = storeRepository.findWithinRadius(lat, lng, matchPoolRadius(radius));
        long loadedAt = System.currentTimeMillis();

        List<Store> inRadius = pool.stream()
                .filter(store -> GeoDistance.between(lat, lng, store.getLat(), store.getLng()) <= radius)
                .toList();
        Set<UUID> verified = verifiedNear(pool, lat, lng, matchPointRadius(radius));

        log.info("공사 매칭 범위: 반경 {}m, 후보 {}건(읽기 {}ms) 중 반경 안 {}건, 매칭 {}건 (전체 {}ms)",
                radius, pool.size(), loadedAt - startedAt, inRadius.size(),
                verified.size(), System.currentTimeMillis() - startedAt);
        return new Scope(inRadius, verified, pool.size());
    }

    /**
     * 한 지점 주변만 좁혀 판정한다.
     *
     * 판정에 영향을 주는 것은 중심 근처의 공사 지점뿐이라 그것만 남긴다 —
     * 성동구 전체 항목을 수천 건 후보와 견주면 대부분 무관한 조합에 시간을 쓴다.
     *
     * pool은 호출한 쪽이 넉넉히 넘겨야 한다(within 이 radius + 600m 로 읽는 이유).
     * 공사 데이터를 못 받으면 빈 집합이다 — 호출한 쪽은 그걸 "확인 안 됨"으로 다룬다.
     */
    Set<UUID> verifiedNear(List<Store> pool, double lat, double lng, double radiusMeters) {
        if (pool.isEmpty()) {
            return Set.of();
        }
        long startedAt = System.currentTimeMillis();
        List<TourStoreMatcher.TourPoint> points = tourDataProvider.seongdongPoints().stream()
                .filter(point -> GeoDistance.between(lat, lng, point.lat(), point.lng())
                        <= radiusMeters)
                .toList();

        if (points.isEmpty()) {
            log.info("공사 매칭(근처): 반경 {}m 안 공사 지점이 없어 미매칭으로 봅니다.", (int) radiusMeters);
            return Set.of();
        }
        Set<UUID> verified = new LinkedHashSet<>(
                matcher.matchAll(points, pool).matched().values().stream()
                        .map(TourStoreMatcher.Match::storeId)
                        .toList());
        log.info("공사 매칭(근처): 공사 {}건 x 후보 {}건 -> {}건 ({}ms)",
                points.size(), pool.size(), verified.size(),
                System.currentTimeMillis() - startedAt);

        return verified;
    }

    /**
     * @param stores   중심에서 반경 안의 가게(가까운 순 보장 없음)
     * @param verified 그중 공사 목록에 오른 가게 id. 반경 밖 후보의 id 도 섞여 있을 수 있다
     * @param poolSize 매칭에 쓴 후보 수(radius + 600m). 로그·점검용
     */
    public record Scope(List<Store> stores, Set<UUID> verified, int poolSize) {

        public boolean isVerified(Store store) {
            return verified.contains(store.getStoreId());
        }
    }
}
