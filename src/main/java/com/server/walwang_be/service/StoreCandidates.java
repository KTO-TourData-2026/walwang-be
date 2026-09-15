package com.server.walwang_be.service;

import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.entity.AvailabilityStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "이 가게를 추천해도 되는가"를 정하는 자리.
 *
 * 코스 추천과 대안 추천이 같은 판단을 써야 한다 — 한쪽에서 걸러진 가게가
 * 다른 쪽에서 나오면 사용자에게는 그냥 일관성 없는 앱이다.
 * 규칙을 두 군데 두면 반드시 갈라지므로 여기 하나만 둔다.
 */
@Component
@RequiredArgsConstructor
public class StoreCandidates {

    /** 화면에 보여줄 태그 개수. */
    private static final int TAG_LIMIT = 3;

    private final StoreSizeStatusRepository storeSizeStatusRepository;
    private final StoreTagCountRepository storeTagCountRepository;

    /**
     * 추천해도 되는 곳인지.
     *
     * 공사 확인이나 리뷰 확인 중 하나라도 있어야 후보가 된다.
     * 둘 다 없는 UNKNOWN은 동반 가능 근거가 없어서 제외한다.
     *
     * IMPOSSIBLE은 무엇보다 앞선다 — 공사 목록에 올라 있어도 현장에서 거절당했다면
     * 그건 직접 겪은 정보라 목록보다 정확하다.
     */
    public boolean hasEvidence(Store store, Set<UUID> verified,
                               Map<UUID, StoreSizeStatus> statusByStore) {
        AvailabilityStatus availability = availability(statusByStore.get(store.getStoreId()));

        if (availability == AvailabilityStatus.IMPOSSIBLE) {
            return false;
        }
        return verified.contains(store.getStoreId())
                || availability == AvailabilityStatus.POSSIBLE;
    }

    /**
     * 요청한 크기에 해당하는 상태만 가게별로 모은다.
     *
     * demo가 정한 축만 읽는다 — 축을 섞으면 가게당 두 행이 들어와
     * toMap이 키 충돌로 터지고, 안 터지더라도 어느 쪽 등급인지 알 수 없다.
     */
    public Map<UUID, StoreSizeStatus> statuses(List<Store> stores, DogSize dogSize, boolean demo) {
        if (stores.isEmpty()) {
            return Map.of();
        }
        return storeSizeStatusRepository.findByStore_StoreIdInAndDemo(storeIds(stores), demo).stream()
                .filter(status -> status.getSize() == dogSize)
                .collect(Collectors.toMap(
                        status -> status.getStore().getStoreId(), status -> status));
    }

    /** 가게별 상위 태그. 한 번에 읽어 N+1을 피한다. */
    public Map<UUID, List<String>> tags(List<Store> stores, boolean demo) {
        if (stores.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> tagsByStore = new LinkedHashMap<>();
        storeTagCountRepository.findByStore_StoreIdInAndDemo(storeIds(stores), demo).stream()
                // 한 번도 선택되지 않은 태그는 붙은 적 없는 것과 같다.
                .filter(tagCount -> tagCount.getCount() > 0)
                .sorted(Comparator.comparingInt(StoreTagCount::getCount).reversed())
                .forEach(tagCount -> tagsByStore
                        .computeIfAbsent(tagCount.getStore().getStoreId(), key -> new ArrayList<>())
                        .add(tagCount.getTag().label()));

        tagsByStore.replaceAll((storeId, tags) ->
                tags.size() > TAG_LIMIT ? List.copyOf(tags.subList(0, TAG_LIMIT)) : List.copyOf(tags));
        return tagsByStore;
    }

    /**
     * 응답 변환. 순서는 넘겨받은 그대로 쓴다 —
     * 코스는 방문 순서, 대안은 가까운 순이고 둘 다 arrivalOrder에 1부터 담긴다.
     */
    public List<CourseStoreResponse> toResponses(List<Store> stores,
                                                 Map<UUID, StoreSizeStatus> statusByStore,
                                                 Map<UUID, List<String>> tagsByStore,
                                                 boolean demo) {
        List<CourseStoreResponse> responses = new ArrayList<>(stores.size());
        for (int i = 0; i < stores.size(); i++) {
            Store store = stores.get(i);
            StoreSizeStatus status = statusByStore.get(store.getStoreId());

            responses.add(new CourseStoreResponse(
                    store.getStoreId(),
                    store.displayName(demo),
                    store.getType(),
                    store.getLat(),
                    store.getLng(),
                    store.displayAddress(demo),
                    i + 1,
                    availability(status),
                    status == null ? 0 : status.getPossibleCount() + status.getImpossibleCount(),
                    tagsByStore.getOrDefault(store.getStoreId(), List.of())));
        }
        return responses;
    }

    private AvailabilityStatus availability(StoreSizeStatus status) {
        return status == null ? AvailabilityStatus.UNKNOWN : status.status();
    }

    private List<UUID> storeIds(List<Store> stores) {
        return stores.stream().map(Store::getStoreId).toList();
    }
}
