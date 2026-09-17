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
import org.springframework.data.domain.Sort;
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

    /**
     * 검색 결과 상한.
     *
     * "스토어" 같은 흔한 낱말은 수천 건이 걸려 응답이 감당이 안 된다.
     * 화면이 한 번에 보여줄 수 있는 양을 넘어서면 나머지는 쓰이지 않으므로 잘라 보낸다.
     */
    private static final int SEARCH_LIMIT = 50;
    /** 상세 화면에 띄울 리뷰 사진 개수. */
    private static final int THUMBNAIL_LIMIT = 5;

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

    /**
     * 가게명 부분 일치 검색.
     *
     * 매칭 기준을 화면에 나가는 이름에 맞춘다 — 시연 모드는 가명, 실제 모드는 실제 상호로 찾는다.
     * 예전에는 언제나 실제 상호로 찾았는데, 시연 중에는 화면에 가명이 보이는 탓에
     * 검색어와 결과가 어긋났다(가명을 쳐도 안 나오고, 실명을 쳐야 나왔다).
     *
     * 시연 모드에서 가명이 없는 가게는 결과에서 빠진다. 실명이 검색으로 새어 나가지
     * 않는다는 뜻이기도 하지만, 그만큼 가명이 채워져 있어야 검색이 제 몫을 한다.
     *
     * 최대 SEARCH_LIMIT 건까지만 준다. 어느 50건이 잘릴지가 요청마다 달라지면 곤란하므로
     * 매칭에 쓴 이름으로 정렬해 자른다 — 순서가 정해져 있어야 결과가 재현된다.
     *
     * 이름 뒤에 storeId 를 덧붙이는 이유는 같은 이름을 쓰는 가게가 실제로 있기 때문이다.
     * 이름만으로 정렬하면 동점인 행들의 순서를 DB가 그때그때 정해서, 경계에 걸린 가게가
     * 어떤 요청에서는 나오고 어떤 요청에서는 빠진다. storeId 까지 봐야 순서가 하나로 정해진다.
     */
    public List<StoreCardResponse> search(String keyword, boolean demo) {
        Sort order = demo ? searchOrder("displayName") : searchOrder("name");
        List<Store> stores = demo
                ? storeRepository.findByDisplayNameContainingIgnoreCase(
                        keyword, PageRequest.of(0, SEARCH_LIMIT, order))
                : storeRepository.findByNameContainingIgnoreCase(
                        keyword, PageRequest.of(0, SEARCH_LIMIT, order));
        return toCards(stores, null, demo);
    }

    /** 검색 정렬 순서. 이름이 같을 때를 storeId 로 갈라 순서를 하나로 정한다. */
    static Sort searchOrder(String nameProperty) {
        return Sort.by(nameProperty).and(Sort.by("storeId"));
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
        // 반경 0 으로 부르면 공사 지점 300m / 후보 600m 가 된다. 지도·코스·대안과 같은 셈이라
        // 목록에서 매칭으로 보였던 가게는 상세에서도 매칭으로 나온다.
        return tourVerifier.within(store.getLat(), store.getLng(), 0).isVerified(store);
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
