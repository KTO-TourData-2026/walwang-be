package com.server.walwang_be.service;

import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * 거절당했을 때 근처의 갈 만한 곳.
 *
 * 문 앞에서 돌아선 사람에게 주는 답이라 조건을 느슨하게 풀지 않는다 —
 * 또 거절당하면 앱을 두 번 원망하게 된다. 코스 추천과 같은 근거 기준을 쓰고,
 * 모자라면 모자란 대로 적게 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlternativeStoreService {

    /**
     * 걸어서 옮겨갈 만한 거리.
     *
     * 500m로 뒀더니 성수동 밀집지를 벗어난 가게에서 0건이 잦아 1km로 넓혔다.
     * 근거를 갖춘 가게가 아직 성동구에 37곳뿐이라, 반경이 좁으면 대안이 아예 안 잡힌다.
     */
    private static final int RADIUS_METERS = 1_000;
    /** 고르기 힘들지 않을 만큼만 준다. */
    private static final int LIMIT = 3;

    private final StoreRepository storeRepository;
    private final TourVerifier tourVerifier;
    private final StoreCandidates storeCandidates;

    /**
     * 기준 가게 주변의 같은 업종 대안. 가까운 순으로 최대 3곳.
     *
     * 조건에 맞는 곳이 없으면 빈 목록이다 — 억지로 채우면 또 거절당할 곳을 보내게 된다.
     * 업종도 근거도 풀지 않는다. 빈 배열은 실패가 아니라 "주변에 대안이 없다"는 답이다.
     */
    @Transactional(readOnly = true)
    public List<CourseStoreResponse> findAlternatives(UUID storeId, DogSize size, boolean demo) {
        Store base = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        // 매칭은 넓은 후보(반경 + 600m)와 견줘야 정확하다. 같은 업종만 넣거나 1km 로만 읽고 돌리면
        // 진짜 주인이 빠진 채 엉뚱한 가게가 공사 항목에 붙는다. 지도·코스와 같은 셈(TourVerifier.within).
        // 공사 응답은 요청 범위(TourDataProvider)에서만 쓰고 저장하지 않는다.
        // 공사 API가 실패하면 verified 가 비어 리뷰 근거(POSSIBLE)만으로 고른다 — 대안 조회는 실패하지 않는다.
        TourVerifier.Scope scope = tourVerifier.within(base.getLat(), base.getLng(), RADIUS_METERS);
        List<Store> nearby = scope.stores();

        // 같은 업종만 남긴다. 카페에서 거절당한 사람에게 약국을 권할 수는 없다.
        // 기준 가게 자신도 여기서 뺀다.
        List<Store> sameCategory = nearby.stream()
                .filter(store -> !store.getStoreId().equals(storeId))
                .filter(store -> base.getType() != null && base.getType().equals(store.getType()))
                .toList();

        if (sameCategory.isEmpty()) {
            return List.of();
        }

        Set<UUID> verified = scope.verified();
        Map<UUID, StoreSizeStatus> statusByStore = storeCandidates.statuses(sameCategory, size, demo);

        List<Store> candidates = sameCategory.stream()
                .filter(store -> storeCandidates.hasEvidence(store, verified, statusByStore))
                .sorted(Comparator.comparingDouble(store -> GeoDistance.between(
                        base.getLat(), base.getLng(), store.getLat(), store.getLng())))
                .limit(LIMIT)
                .toList();

        log.info("대안 조회: 기준 {} ({}), 반경 {}m 안 같은 업종 {}곳(공사 매칭 {}곳) -> {}곳 [매칭 후보 {}곳]",
                storeId, base.getType(), RADIUS_METERS, sameCategory.size(),
                sameCategory.stream().filter(store -> verified.contains(store.getStoreId())).count(),
                candidates.size(), scope.poolSize());

        return storeCandidates.toResponses(
                candidates, statusByStore, storeCandidates.tags(candidates, demo), demo);
    }
}
