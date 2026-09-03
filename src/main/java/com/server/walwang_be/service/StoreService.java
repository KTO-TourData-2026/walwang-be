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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    /** 상세 화면에 띄울 리뷰 사진 개수. */
    private static final int THUMBNAIL_LIMIT = 5;

    private final StoreRepository storeRepository;
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

    public StoreDetailResponse findDetail(UUID storeId, boolean demo) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        List<String> tags = storeTagCountRepository.findByStore_StoreIdOrderByCountDesc(storeId).stream()
                // 한 번도 선택되지 않은 태그는 붙은 적 없는 것과 같으므로 내보내지 않는다.
                .filter(tagCount -> tagCount.getCount() > 0)
                .map(StoreTagCount::getTag)
                .map(StoreTag::label)
                .toList();

        List<SizeStatusResponse> sizeStatus =
                toSizeStatus(storeSizeStatusRepository.findByStore_StoreId(storeId), null);

        List<String> thumbnailUrls = reviewRepository
                .findByStore_StoreIdAndDemoAndBlindStatusOrderByCreatedAtDesc(
                        storeId, demo, BlindStatus.NORMAL, PageRequest.of(0, THUMBNAIL_LIMIT))
                .map(Review::getThumbnailUrl)
                .getContent();

        return StoreDetailResponse.of(store, tags, sizeStatus, thumbnailUrls, demo);
    }

    public List<ReviewResponse> findReviews(UUID storeId, int page, int size, boolean demo) {
        return reviewRepository
                .findByStore_StoreIdAndDemoAndBlindStatusOrderByCreatedAtDesc(
                        storeId, demo, BlindStatus.NORMAL, PageRequest.of(page, size))
                .map(ReviewResponse::from)
                .getContent();
    }

    /**
     * 가게 저장. 이미 저장돼 있으면 아무것도 하지 않는다 —
     * 같은 버튼을 두 번 눌러도 실패로 보이지 않게 하려는 것이다.
     */
    @Transactional
    public void saveStore(UUID userId, UUID storeId) {
        if (savedStoreRepository.existsByUser_UserIdAndStore_StoreId(userId, storeId)) {
            return;
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        savedStoreRepository.save(SavedStore.builder()
                // 저장만 할 것이라 User를 실제로 읽어올 필요가 없다.
                .user(userRepository.getReferenceById(userId))
                .store(store)
                .build());
    }

    /** 저장 해제. 저장돼 있지 않아도 성공으로 본다(저장과 같은 이유). */
    @Transactional
    public void unsaveStore(UUID userId, UUID storeId) {
        savedStoreRepository.deleteByUser_UserIdAndStore_StoreId(userId, storeId);
    }

    /** 내가 저장한 가게 목록. 최근에 저장한 것부터. */
    public List<StoreCardResponse> findSavedStores(UUID userId, int page, int size) {
        List<Store> stores = savedStoreRepository
                .findByUser_UserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(SavedStore::getStore)
                .getContent();

        // 저장 순서를 유지해야 하므로 toCards가 넘겨받은 순서를 그대로 쓴다.
        return toCards(stores, null, false);
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
                storeSizeStatusRepository.findByStore_StoreIdIn(storeIds).stream()
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
