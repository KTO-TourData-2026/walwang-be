package com.server.walwang_be.service;

import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    private final TourVerifier tourVerifier;
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
        // 후보를 반경 + 600m 로 한 번 읽어 매칭하고, 화면(요청 반경) 안으로 다시 좁힌다.
        // 코스·대안·상세와 같은 셈이라 같은 가게는 어느 화면에서든 같은 판정을 받는다.
        TourVerifier.Scope scope = tourVerifier.within(lat, lng, radius);
        if (scope.verified().isEmpty()) {
            return List.of();
        }
        List<Store> inView = scope.stores().stream()
                .filter(scope::isVerified)
                .toList();

        // 크기별 상태는 StoreService가 한 번에 읽어 붙인다(N+1 방지).
        return storeService.toCards(excludeImpossible(inView, size, demo), size, demo);
    }

    /**
     * 그 크기로 동반 불가라고 확인된 곳을 지도에서 뺀다.
     *
     * UNKNOWN은 남긴다 — 아직 아무도 안 가본 곳이라는 뜻이지 못 간다는 뜻이 아니다.
     * 그래서 리뷰가 쌓이기 전에는 아무것도 걸러지지 않는 것이 정상이다.
     */
    private List<Store> excludeImpossible(List<Store> stores, DogSize size, boolean demo) {
        if (size == null || stores.isEmpty()) {
            return stores;
        }
        Map<UUID, StoreSizeStatus> statusByStore = storeSizeStatusRepository
                .findByStore_StoreIdInAndDemo(stores.stream().map(Store::getStoreId).toList(), demo).stream()
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

}
