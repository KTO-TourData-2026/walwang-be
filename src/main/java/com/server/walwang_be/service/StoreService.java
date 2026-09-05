package com.server.walwang_be.service;

import com.server.walwang_be.dto.ReviewResponse;
import com.server.walwang_be.dto.SizeStatusResponse;
import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.dto.StoreDetailResponse;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.SavedStore;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreTag;
import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.repo.ReviewRepository;
import com.server.walwang_be.repo.SavedStoreRepository;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import com.server.walwang_be.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    /** 상세 화면에 띄울 리뷰 사진 개수. */
    private static final int THUMBNAIL_LIMIT = 5;
    /** 매칭 판정에 쓸 후보 반경. 공사 지점 반경(300m)의 두 배다. */
    private static final int MATCH_POOL_RADIUS_METERS =
            (int) (TourStoreMatcher.WIDE_RADIUS_METERS * 2);

    private final StoreRepository storeRepository;
    private final TourVerifier tourVerifier;
    private final StoreSizeStatusRepository storeSizeStatusRepository;
    private final StoreTagCountRepository storeTagCountRepository;
    private final ReviewRepository reviewRepository;
    private final SavedStoreRepository savedStoreRepository;
    private final UserRepository userRepository;

    /** 지도 핀 조회. size가 null이면 두 크기를 모두 내려준다. */
    public List<StoreCardResponse> findNearby(double lat, double lng, int radius,
                                              DogSize size, boolean demo) {
        return toCards(storeRepository.findWithinRadius(lat, lng, radius), size, demo);
    }

    /** 가게명 부분 일치 검색. */
    public List<StoreCardResponse> search(String keyword, boolean demo) {
        // 검색은 언제나 실제 상호로 한다. 가명은 응답에만 쓴다.
        return toCards(storeRepository.findByNameContainingIgnoreCase(keyword), null, demo);
    }

    /**
     * 가게 상세.
     *
     * viewerId는 썸네일을 고르는 데만 쓴다 — 인증을 거치지 않은 리뷰의 사진은
     * 작성자 본인에게만 보인다. 비로그인이면 null이 들어와 전부 빠진다.
     */
    public StoreDetailResponse findDetail(UUID storeId, boolean demo, UUID viewerId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        List<String> tags = storeTagCountRepository
                .findByStore_StoreIdAndDemoOrderByCountDesc(storeId, demo).stream()
                // 한 번도 선택되지 않은 태그는 붙은 적 없는 것과 같으므로 내보내지 않는다.
                .filter(tagCount -> tagCount.getCount() > 0)
                .map(StoreTagCount::getTag)
                .map(StoreTag::label)
                .toList();

        List<StoreSizeStatus> statuses = storeSizeStatusRepository
                .findByStore_StoreIdAndDemo(storeId, demo);
        List<SizeStatusResponse> sizeStatus = toSizeStatus(statuses, null);
        // 가게 전체의 "마지막 확인" 은 크기별 값 중 가장 최근 것이다.
        Instant lastVerifiedAt = statuses.stream()
                .map(StoreSizeStatus::getLastVerifiedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        List<String> thumbnailUrls = reviewRepository
                .findVisibleByStore(storeId, demo, BlindStatus.NORMAL, viewerId,
                        PageRequest.of(0, THUMBNAIL_LIMIT))
                .map(Review::getThumbnailUrl)
                .getContent();

        return StoreDetailResponse.of(store, tags, sizeStatus, lastVerifiedAt, thumbnailUrls, demo,
                petVerified(store));
    }

    /**
     * 이 가게가 공사 반려동물 동반 목록에 있는지. 요청 때마다 공사 API로 확인한다.
     *
     * 조회한 공사 응답은 저장하지 않는다 — TourDataProvider가 @RequestScope라
     * 이 요청이 끝나면 함께 사라진다.
     *
     * 실패하면 false다. 상세 조회 자체를 실패로 만들지는 않는다 —
     * 참고 배지 하나 때문에 가게 정보를 못 보게 되는 쪽이 더 나쁘다.
     */
    private boolean petVerified(Store store) {
        // 판정에 영향을 주는 이웃까지 담는다. 근처 공사 지점(300m)의 진짜 주인이
        // 그 지점에서 다시 300m 떨어져 있을 수 있어 두 배로 잡는다.
        List<Store> pool = storeRepository.findWithinRadius(
                store.getLat(), store.getLng(), MATCH_POOL_RADIUS_METERS);

        return tourVerifier.verifiedNear(pool, store.getLat(), store.getLng(),
                TourStoreMatcher.WIDE_RADIUS_METERS).contains(store.getStoreId());
    }

    /**
     * 가게의 리뷰 목록.
     *
     * viewerId는 두 가지에 쓴다 — 응답의 mine을 채우고, 인증을 거치지 않은 리뷰를
     * 작성자 본인에게만 남긴다. 비로그인이면 null이 들어와 mine은 전부 false가 되고
     * verified=false인 리뷰는 하나도 나오지 않는다.
     */
    public List<ReviewResponse> findReviews(UUID storeId, int page, int size, boolean demo,
                                            UUID viewerId) {
        return reviewRepository
                .findVisibleByStore(storeId, demo, BlindStatus.NORMAL, viewerId,
                        PageRequest.of(page, size))
                .map(review -> ReviewResponse.from(review, viewerId))
                .getContent();
    }

    /**
     * 가게 저장. 이미 저장돼 있으면 아무것도 하지 않는다 —
     * 같은 버튼을 두 번 눌러도 실패로 보이지 않게 하려는 것이다.
     *
     * demo가 정한 쪽에 저장한다. 같은 가게를 시연 쪽과 실제 쪽에 각각 담을 수 있다.
     */
    @Transactional
    public void saveStore(UUID userId, UUID storeId, boolean demo) {
        if (savedStoreRepository.existsByUser_UserIdAndStore_StoreIdAndDemo(userId, storeId, demo)) {
            return;
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        savedStoreRepository.save(SavedStore.builder()
                // 저장만 할 것이라 User를 실제로 읽어올 필요가 없다.
                .user(userRepository.getReferenceById(userId))
                .store(store)
                .demo(demo)
                .build());
    }

    /**
     * 저장 해제. 저장돼 있지 않아도 성공으로 본다(저장과 같은 이유).
     *
     * demo가 정한 쪽만 지운다. 저장이 축을 갈라 담으므로 해제도 같은 축이어야 한다 —
     * 축을 빼고 지우면 시연 중에 누른 해제가 실제로 저장해 둔 가게까지 날린다.
     */
    @Transactional
    public void unsaveStore(UUID userId, UUID storeId, boolean demo) {
        savedStoreRepository.deleteByUser_UserIdAndStore_StoreIdAndDemo(userId, storeId, demo);
    }

    /** 내가 저장한 가게 목록. 최근에 저장한 것부터. demo가 정한 쪽만 나간다. */
    public List<StoreCardResponse> findSavedStores(UUID userId, int page, int size, boolean demo) {
        List<Store> stores = savedStoreRepository
                .findByUser_UserIdAndDemoOrderByCreatedAtDesc(userId, demo, PageRequest.of(page, size))
                .map(SavedStore::getStore)
                .getContent();

        // 저장 순서를 유지해야 하므로 toCards가 넘겨받은 순서를 그대로 쓴다.
        return toCards(stores, null, demo);
    }

    /**
     * 가게 목록에 크기별 상태를 붙인다.
     * 가게마다 상태를 따로 조회하면 N+1이 되므로 한 번에 읽어 메모리에서 묶는다.
     */
    List<StoreCardResponse> toCards(List<Store> stores, DogSize size, boolean demo) {
        if (stores.isEmpty()) {
            return List.of();
        }
        List<UUID> storeIds = stores.stream().map(Store::getStoreId).toList();

        Map<UUID, List<StoreSizeStatus>> statusByStore =
                storeSizeStatusRepository.findByStore_StoreIdInAndDemo(storeIds, demo).stream()
                        // LAZY 프록시라도 FK인 store_id는 초기화 없이 읽힌다.
                        .collect(Collectors.groupingBy(status -> status.getStore().getStoreId()));

        return stores.stream()
                .map(store -> StoreCardResponse.of(store,
                        toSizeStatus(statusByStore.getOrDefault(store.getStoreId(), List.of()), size), demo))
                .toList();
    }

    /** size가 주어지면 그 크기만, null이면 전체를 크기 순서대로 내보낸다. */
    private List<SizeStatusResponse> toSizeStatus(List<StoreSizeStatus> statuses, DogSize size) {
        return statuses.stream()
                .filter(status -> size == null || status.getSize() == size)
                .sorted(Comparator.comparing(StoreSizeStatus::getSize))
                .map(SizeStatusResponse::from)
                .toList();
    }
}
