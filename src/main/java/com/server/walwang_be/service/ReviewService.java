package com.server.walwang_be.service;

import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.client.ReceiptOcrClient;
import com.server.walwang_be.client.SupabaseStorageClient;
import com.server.walwang_be.dto.RecomputeResultResponse;
import com.server.walwang_be.dto.ReceiptVerifyResponse;
import com.server.walwang_be.dto.ReviewCreateRequest;
import com.server.walwang_be.dto.ReviewCreateResponse;
import com.server.walwang_be.dto.SizeStatusResponse;
import com.server.walwang_be.dto.StoreStatusSummary;
import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreTag;
import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.PassportRepository;
import com.server.walwang_be.repo.ReceiptTokenRepository;
import com.server.walwang_be.repo.ReviewReportRepository;
import com.server.walwang_be.repo.ReviewRepository;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import com.server.walwang_be.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewService {

    /**
     * 리뷰 점수가 절반으로 줄어드는 데 걸리는 기간.
     * 1년 전 "출입 가능"이 어제 것과 같은 무게를 가지면 안 되기 때문에 둔다.
     */
    private static final double HALF_LIFE_DAYS = 180.0;

    private static final long MAX_RECEIPT_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);

    private final ReviewRepository reviewRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final StoreRepository storeRepository;
    private final StoreSizeStatusRepository storeSizeStatusRepository;
    private final StoreTagCountRepository storeTagCountRepository;
    private final UserRepository userRepository;
    private final ReceiptTokenRepository receiptTokenRepository;
    private final SupabaseStorageClient storageClient;
    private final ImageProcessor imageProcessor;
    private final ReceiptOcrClient ocrClient;
    private final PassportService passportService;
    private final PassportRepository passportRepository;
    private final EmbeddingService embeddingService;

    /**
     * 영수증 인증. 상호명이 맞으면 30분짜리 토큰을 내주고,
     * 리뷰 등록은 그 토큰이 있어야만 통과한다 — 가보지 않은 가게에 리뷰를 쓰지 못하게 하는 장치다.
     */
    /**
     * @param demo 대조는 언제나 실제 상호로 한다 — 영수증에 찍힌 건 실명이라
     *             가명으로 맞춰보면 무엇도 통과하지 못한다. 가명은 응답에만 쓴다.
     */
    @Transactional(readOnly = true)
    public ReceiptVerifyResponse verifyReceipt(UUID userId, UUID storeId,
                                               MultipartFile receipt, boolean demo) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));
        validateReceiptFile(receipt);

        // 읽기 실패(422)와 OCR 장애(503)는 클라이언트가 다르게 대응해야 하므로
        // 여기서 잡지 않고 예외 그대로 올려보낸다.
        ReceiptOcrClient.OcrResult ocr = ocrClient.verify(receipt, storeId, store.getName());
        // OCR이 읽어온 건 영수증의 실제 상호다. 시연 중에는 그대로 내보내지 않는다.
        String matchedName = demo ? store.displayName(true) : ocr.matchedName();

        // 통과 기준은 AI가 준 verified가 아니라 우리가 정한다 —
        // 임계값을 조정할 때 AI 서비스를 다시 배포하지 않아도 되게 하려는 것이다.
        double similarity = ocr.similarity();

        if (similarity < StoreNameMatcher.THRESHOLD) {
            return ReceiptVerifyResponse.mismatch(matchedName, similarity);
        }
        String token = UUID.randomUUID().toString();
        receiptTokenRepository.save(token, userId, storeId);
        return ReceiptVerifyResponse.verified(matchedName, similarity, token);
    }

    /**
     * 리뷰 등록.
     *
     * @param demo 시연 모드. OCR 인증을 건너뛰는 대신 그 리뷰는 실제 데이터에 섞이지 않는다 —
     *             verified=false로 남아 남에게 보이지 않고, 집계·임베딩도 건드리지 않는다.
     *             도장은 정상적으로 찍힌다(여권도 is_demo=true로 생긴다).
     */
    @Transactional
    public ReviewCreateResponse create(UUID userId, ReviewCreateRequest request,
                                       MultipartFile photo, boolean demo) {
        // 시연 모드는 영수증을 받지 않는다. 가보지 않은 가게에도 쓸 수 있다는 뜻이라
        // 아래에서 verified=false로 남겨 실제 데이터와 갈라 둔다.
        // 거절당한 방문도 영수증을 요구하지 않는다 — 결제를 못 했으니 낼 영수증이 없다.
        // 토큰을 들고 왔다면 재사용을 막으려고 그대로 소비한다.
        if (!demo && (request.dogAllowed() || hasReceiptToken(request))) {
            consumeReceiptToken(userId, request);
        }

        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        Set<StoreTag> tags = toTags(request.tags());

        // 사진은 선택이다. 거절당한 리뷰는 남길 사진이 없다.
        String photoUrl = null;
        String thumbnailUrl = null;
        if (photo != null && !photo.isEmpty()) {
            validateImageFile(photo);
            ImageProcessor.ProcessedImage processed = imageProcessor.process(photo);
            // storeId는 UUID다. %d로 찍으면 IllegalFormatConversionException이 나고,
            // 트랜잭션 안이라 리뷰까지 통째로 롤백된다.
            String base = "reviews/%s/%s".formatted(store.getStoreId(), UUID.randomUUID());
            photoUrl = storageClient.upload(base + "-original.jpg", processed.original(), MediaType.IMAGE_JPEG);
            thumbnailUrl = storageClient.upload(base + "-thumb.jpg", processed.thumbnail(), MediaType.IMAGE_JPEG);
        }

        User user = userRepository.getReferenceById(userId);
        Review review = reviewRepository.save(Review.builder()
                .user(user)
                .store(store)
                .dogAllowed(request.dogAllowed())
                .dogSize(request.dogSize())
                .photoUrl(photoUrl)
                .thumbnailUrl(thumbnailUrl)
                .content(request.content())
                .tags(tags)
                .demo(demo)
                // 시연 리뷰만 인증에서 뺀다. 영수증 없이 쓴 거절 리뷰는 인증으로 친다 —
                // 거절을 증명할 수단이 구조적으로 없고, 빼면 "불가" 정보가 영영 쌓이지 않는다.
                // 허위 거절 리뷰는 신고와 재가입 차단으로 대응한다.
                .verified(!demo)
                .build());

        reviewRepository.flush();
        // 시연 리뷰는 어느 축의 점수도 움직이지 않는다 — 인증되지 않은 리뷰라
        // 시연 쪽 집계에서도 빠진다. 시연 쪽 행은 목 리뷰로만 채워진다.
        if (review.isVerified()) {
            recompute(store, demo);
        }

        // 도장은 동반 가능했던 방문에만 찍는다 — 거절당한 기록은 여권에 남기지 않는다.
        // 시연 리뷰에도 그대로 찍는다. 도장 생성이 시연에서 보여줄 것 중 하나이고,
        // 여권은 리뷰의 is_demo를 물려받아 시연 쪽에만 남는다.
        Passport passport = request.dogAllowed()
                ? passportService.createFor(user, store, review)
                : null;

        // 세그멘테이션을 여기서 기다린다. 프론트가 캐싱 때문에 등록 응답에서
        // 최종 stampUrl을 받아야 해서, 커밋 뒤 비동기로 채우던 걸 안으로 들였다.
        // ai.stamp-timeout(기본 8초)에서 끊기고, 못 받으면 FALLBACK으로 확정된다.
        // 예외는 올라오지 않는다 — AI가 죽어도 리뷰 등록은 성공해야 한다.
        StampGenerator.StampResult stamp = passport == null
                ? null
                : passportService.generateStampNow(passport.getPassportId());

        // 리뷰가 하나 붙으면 그 가게의 설명이 달라진다. AI가 벡터를 다시 계산하도록
        // 알려만 둔다 — 실패해도 리뷰 등록은 그대로 성공이다.
        // 시연 리뷰는 부르지 않는다. 임베딩은 실제 리뷰로 만든 것이라
        // 시연 중에 쓴 문장이 섞이면 그 표현으로 가게가 검색된다.
        if (review.isVerified()) {
            afterCommit(() -> embeddingService.refreshAsync(store.getStoreId()));
        }

        return new ReviewCreateResponse(
                review.getReviewId(),
                // 확정된 최종 주소가 그대로 나간다. 도장을 찍지 않은 리뷰(동반 불가)는
                // 화면이 비지 않도록 기본 발도장 자리만 채워 보낸다.
                stamp == null ? PawStampClient.DEFAULT_STAMP_URL : stamp.stampUrl(),
                stamp == null ? null : stamp.status(),
                // 방금 쓴 리뷰가 속한 축의 상태를 돌려준다. 다른 축을 보여주면
                // 화면이 방금 한 일과 무관한 등급을 그린다.
                summarize(store, demo));
    }

    /** 내 리뷰 삭제. 남의 리뷰는 존재를 알려주지 않고 403으로 끊는다. */
    @Transactional
    public void delete(UUID userId, UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 리뷰입니다."));

        if (!review.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("자신이 쓴 리뷰만 삭제할 수 있습니다.");
        }
        Store store = review.getStore();
        // 엔티티가 사라진 뒤에는 못 읽으므로 URL과 인증 여부를 미리 빼둔다.
        String photoUrl = review.getPhotoUrl();
        String thumbnailUrl = review.getThumbnailUrl();
        // 도장 파일도 같은 이유로 먼저 챙긴다. 여권 행이 지워지면 주소를 잃는다.
        List<String> stampUrls = passportRepository.findStoredStampUrlsByReview(reviewId);
        boolean counted = review.isVerified();
        // 지운 리뷰가 속한 축을 다시 센다. 반대쪽 축은 이 리뷰를 세지 않았으므로 그대로 둔다.
        boolean countedOnDemoAxis = review.isDemo();

        // 여권과 신고가 리뷰를 참조하므로 먼저 지운다.
        // 다녀온 증거가 사라지면 도장도 남지 않고, 사라진 리뷰를 향한 신고도 의미가 없다.
        passportRepository.deleteByReview_ReviewId(reviewId);
        reviewReportRepository.deleteByReview_ReviewId(reviewId);
        reviewRepository.delete(review);
        // 재집계가 삭제된 리뷰를 다시 세지 않도록 먼저 DB에 반영한다.
        reviewRepository.flush();

        // 애초에 세지도, 임베딩에 넣지도 않은 리뷰라면 빠져도 달라지는 게 없다.
        // 등록 때 부르지 않았으니 삭제 때도 부르지 않는다.
        if (counted) {
            recompute(store, countedOnDemoAxis);
            // 리뷰가 빠져도 가게 설명이 달라진다. 등록 때와 같은 이유로 갱신을 알린다.
            afterCommit(() -> embeddingService.refreshAsync(store.getStoreId()));
        }
        List<String> files = new ArrayList<>(stampUrls);
        files.add(photoUrl);
        files.add(thumbnailUrl);
        deleteFilesAfterCommit(files);
    }

    /**
     * 파일 삭제는 커밋이 끝난 뒤에 한다.
     *
     * 트랜잭션 안에서 지워버리면, 이후 커밋이 실패해 롤백됐을 때
     * 리뷰는 살아있는데 사진만 사라진 상태가 된다 — 화면에 깨진 이미지가 남는다.
     * 반대로 커밋 후 삭제가 실패하면 고아 파일이 남을 뿐이라 화면은 멀쩡하다.
     */
    private void deleteFilesAfterCommit(List<String> publicUrls) {
        afterCommit(() -> deleteFiles(publicUrls));
    }

    /** 롤백되면 되돌릴 수 없는 일(파일 삭제, 외부 호출)은 커밋 확정 후에 한다. */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** 빈 자리는 넘긴다 — 사진 없는 리뷰나 폴백 도장이라 지울 파일이 없는 경우다. */
    private void deleteFiles(List<String> publicUrls) {
        for (String publicUrl : publicUrls) {
            storageClient.deleteByPublicUrlQuietly(publicUrl);
        }
    }

    private boolean hasReceiptToken(ReviewCreateRequest request) {
        return request.receiptToken() != null && !request.receiptToken().isBlank();
    }

    private void consumeReceiptToken(UUID userId, ReviewCreateRequest request) {
        // DTO에서 @NotBlank를 뺐으므로(시연 모드·거절 리뷰가 토큰 없이 온다) 여기서 막는다.
        // 400을 그대로 유지하려고 IllegalArgumentException을 쓴다.
        if (!hasReceiptToken(request)) {
            throw new IllegalArgumentException("영수증 인증이 필요합니다.");
        }
        ReceiptTokenRepository.Issued issued = receiptTokenRepository.find(request.receiptToken())
                .orElseThrow(() -> new AccessDeniedException("영수증 인증이 만료되었습니다. 다시 인증해 주세요."));

        // 토큰이 새어 나가도 남의 계정이나 다른 가게에는 쓸 수 없어야 한다.
        if (!issued.userId().equals(userId) || !issued.storeId().equals(request.storeId())) {
            throw new AccessDeniedException("영수증 인증 정보가 요청과 일치하지 않습니다.");
        }
        // 영수증 한 장에 리뷰 하나. 재사용을 막으려고 즉시 없앤다.
        receiptTokenRepository.delete(request.receiptToken());
    }

    /**
     * 없는 code는 여기까지 오지 않는다 — Jackson이 역직렬화 단계에서 걸러
     * HttpMessageNotReadableException(400)으로 끝난다.
     */
    private Set<StoreTag> toTags(List<StoreTag> tags) {
        return (tags == null || tags.isEmpty())
                ? EnumSet.noneOf(StoreTag.class)
                : EnumSet.copyOf(tags);
    }

    /**
     * 가게 하나를 다시 집계하고 결과를 돌려준다.
     *
     * 관리자가 리뷰를 가린 뒤 부르는 자리다. blind_status를 DB에서 직접 바꾸면
     * 등록·삭제 어느 쪽도 아니라 recompute가 저절로 돌지 않는다 —
     * 그대로 두면 목록에서는 사라진 리뷰가 점수에는 남는다.
     *
     * 임베딩 갱신도 여기서 같이 알린다 — 가려진 리뷰의 문장이 임베딩에 남아 있으면
     * 화면에서 지운 표현으로 그 가게가 검색된다. demo=true는 부르지 않는다.
     * 목 데이터는 AI팀이 DB에 직접 넣으면서 임베딩까지 함께 채운다.
     */
    @Transactional
    public RecomputeResultResponse recomputeStore(UUID storeId, boolean demo) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        recompute(store, demo);
        // 갱신된 값을 그대로 읽어 보여주려면 먼저 반영돼야 한다.
        storeSizeStatusRepository.flush();

        int counted = reviewRepository
                .findByStore_StoreIdAndDemoAndVerifiedTrueAndBlindStatus(
                        storeId, demo, BlindStatus.NORMAL)
                .size();

        List<RecomputeResultResponse.SizeResult> sizes =
                storeSizeStatusRepository.findByStore_StoreIdAndDemo(storeId, demo).stream()
                        .sorted(Comparator.comparing(StoreSizeStatus::getSize))
                        .map(RecomputeResultResponse.SizeResult::from)
                        .toList();

        if (!demo) {
            afterCommit(() -> embeddingService.refreshAsync(storeId));
        }
        // 관리자용 응답이라도 가명 정책 밖에 두지 않는다 — demo가 정한 이름으로 나간다.
        return new RecomputeResultResponse(storeId, store.displayName(demo), counted, sizes);
    }

    /**
     * 가게의 리뷰를 전부 다시 세어 점수·건수·태그를 갈아끼운다.
     *
     * 등록·삭제 양쪽에서 같은 함수를 부르는 게 핵심이다.
     * 더하고 빼는 방식이면 시간가중 점수가 원래대로 돌아오지 않아 오차가 쌓인다.
     *
     * 축 없는 오버로드는 두지 않는다 — 어느 쪽을 갱신하는지는 부르는 쪽이
     * 언제나 알고 있고, 기본값을 두면 잘못된 축을 조용히 덮어쓰게 된다.
     *
     * @param demo true면 시연용 목 리뷰(is_demo=true)로 집계해 시연 쪽 행에 쓴다
     */
    public void recompute(Store store, boolean demo) {
        // demo가 정한 쪽 리뷰만 센다. 가려진 리뷰는 어느 쪽이든 뺀다 —
        // 화면에서 감췄는데 점수에 남아 있으면 가린 의미가 없다.
        // 인증을 거치지 않은 리뷰도 같은 이유로 뺀다 — 영수증 없이 쓸 수 있는 리뷰가
        // 점수를 움직이면 인증을 둔 의미가 없다.
        List<Review> reviews = reviewRepository.findByStore_StoreIdAndDemoAndVerifiedTrueAndBlindStatus(
                store.getStoreId(), demo, BlindStatus.NORMAL);
        Instant now = Instant.now();

        for (StoreSizeStatus status
                : storeSizeStatusRepository.findByStore_StoreIdAndDemo(store.getStoreId(), demo)) {
            double possibleScore = 0;
            double impossibleScore = 0;
            int possibleCount = 0;
            int impossibleCount = 0;
            Instant lastVerified = null;

            for (Review review : reviews) {
                if (review.getDogSize() != status.getSize()) {
                    continue;
                }
                double weight = weight(review.getCreatedAt(), now);
                if (review.isDogAllowed()) {
                    possibleScore += weight;
                    possibleCount++;
                } else {
                    impossibleScore += weight;
                    impossibleCount++;
                }
                if (lastVerified == null || review.getCreatedAt().isAfter(lastVerified)) {
                    lastVerified = review.getCreatedAt();
                }
            }

            status.applyRecomputed(possibleScore, impossibleScore,
                    possibleCount, impossibleCount, lastVerified);
        }

        recomputeTags(store, reviews, demo);
    }

    private void recomputeTags(Store store, List<Review> reviews, boolean demo) {
        Map<StoreTag, Integer> counts = new EnumMap<>(StoreTag.class);
        for (Review review : reviews) {
            for (StoreTag tag : review.getTags()) {
                counts.merge(tag, 1, Integer::sum);
            }
        }

        Map<StoreTag, StoreTagCount> existing = new EnumMap<>(StoreTag.class);
        storeTagCountRepository.findByStore_StoreIdAndDemoOrderByCountDesc(store.getStoreId(), demo)
                .forEach(row -> existing.put(row.getTag(), row));

        counts.forEach((tag, count) -> {
            StoreTagCount row = existing.remove(tag);
            if (row == null) {
                row = storeTagCountRepository.save(StoreTagCount.builder()
                        .store(store)
                        .tag(tag)
                        .demo(demo)
                        .build());
            }
            row.applyRecomputed(count);
        });

        // 마지막 리뷰가 지워져 아무도 고르지 않게 된 태그는 0으로 되돌린다.
        existing.values().forEach(row -> row.applyRecomputed(0));
    }

    /** 오래된 리뷰일수록 가볍게 센다. HALF_LIFE_DAYS마다 무게가 절반이 된다. */
    private double weight(Instant createdAt, Instant now) {
        double days = (double) Duration.between(createdAt, now).toSeconds()
                / Duration.ofDays(1).toSeconds();
        return Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    private StoreStatusSummary summarize(Store store, boolean demo) {
        List<SizeStatusResponse> sizeStatus =
                storeSizeStatusRepository.findByStore_StoreIdAndDemo(store.getStoreId(), demo).stream()
                        .sorted(Comparator.comparing(StoreSizeStatus::getSize))
                        .map(SizeStatusResponse::from)
                        .toList();
        return new StoreStatusSummary(store.getStoreId(), sizeStatus);
    }

    private void validateReceiptFile(MultipartFile receipt) {
        if (receipt == null || receipt.isEmpty()) {
            throw new IllegalArgumentException("영수증 이미지가 필요합니다.");
        }
        if (receipt.getSize() > MAX_RECEIPT_BYTES) {
            throw new IllegalArgumentException("영수증 이미지는 10MB 이하여야 합니다.");
        }
        validateImageFile(receipt);
    }

    private void validateImageFile(MultipartFile file) {
        if (!ALLOWED_IMAGE_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("JPEG 또는 PNG 이미지만 올릴 수 있습니다.");
        }
    }
}
