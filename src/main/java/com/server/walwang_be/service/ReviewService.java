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

    /**
     * 영수증 인증. 상호명이 맞으면 30분짜리 토큰을 내주고,
     * 리뷰 등록은 그 토큰이 있어야만 통과한다 — 가보지 않은 가게에 리뷰를 쓰지 못하게 하는 장치다.
     */
    @Transactional(readOnly = true)
    public ReceiptVerifyResponse verifyReceipt(UUID userId, UUID storeId, MultipartFile receipt) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));
        validateReceiptFile(receipt);

        // 읽기 실패(422)와 OCR 장애(503)는 클라이언트가 다르게 대응해야 하므로
        // 여기서 잡지 않고 예외 그대로 올려보낸다.
        ReceiptOcrClient.OcrResult ocr = ocrClient.verify(receipt, storeId, store.getName());
        String matchedName = ocr.matchedName();

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

    @Transactional
    public ReviewCreateResponse create(UUID userId, ReviewCreateRequest request, MultipartFile photo) {
        consumeReceiptToken(userId, request);

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
                // 클라이언트가 뭘 보내든 실제 이용자의 리뷰는 항상 false다.
                .demo(false)
                .build());

        reviewRepository.flush();
        recompute(store);

        // 도장은 동반 가능했던 방문에만 찍는다 — 거절당한 기록은 여권에 남기지 않는다.
        UUID passportId = request.dogAllowed()
                ? passportService.createFor(user, store, review).getPassportId()
                : null;
        if (passportId != null) {
            // 세그멘테이션은 오래 걸려 여기서 기다리지 않는다.
            // 다른 스레드에서 도므로 커밋이 끝난 뒤에 넘겨야 여권 행이 보인다.
            afterCommit(() -> passportService.generateStampAsync(passportId));
        }

        return new ReviewCreateResponse(
                review.getReviewId(),
                // 실제 도장은 여권에서 확인한다. 여기서는 항상 기본 발도장이 나간다.
                PawStampClient.DEFAULT_STAMP_URL,
                summarize(store));
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
        // 엔티티가 사라진 뒤에는 못 읽으므로 URL을 미리 빼둔다.
        String photoUrl = review.getPhotoUrl();
        String thumbnailUrl = review.getThumbnailUrl();

        // 여권과 신고가 리뷰를 참조하므로 먼저 지운다.
        // 다녀온 증거가 사라지면 도장도 남지 않고, 사라진 리뷰를 향한 신고도 의미가 없다.
        passportRepository.deleteByReview_ReviewId(reviewId);
        reviewReportRepository.deleteByReview_ReviewId(reviewId);
        reviewRepository.delete(review);
        // 재집계가 삭제된 리뷰를 다시 세지 않도록 먼저 DB에 반영한다.
        reviewRepository.flush();

        recompute(store);
        deletePhotosAfterCommit(photoUrl, thumbnailUrl);
    }

    /**
     * 사진 삭제는 커밋이 끝난 뒤에 한다.
     *
     * 트랜잭션 안에서 지워버리면, 이후 커밋이 실패해 롤백됐을 때
     * 리뷰는 살아있는데 사진만 사라진 상태가 된다 — 화면에 깨진 이미지가 남는다.
     * 반대로 커밋 후 삭제가 실패하면 고아 파일이 남을 뿐이라 화면은 멀쩡하다.
     */
    private void deletePhotosAfterCommit(String... publicUrls) {
        afterCommit(() -> deletePhotos(publicUrls));
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

    private void deletePhotos(String... publicUrls) {
        for (String publicUrl : publicUrls) {
            storageClient.deleteByPublicUrlQuietly(publicUrl);
        }
    }

    private void consumeReceiptToken(UUID userId, ReviewCreateRequest request) {
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
     */
    @Transactional
    public RecomputeResultResponse recomputeStore(UUID storeId, boolean demo) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 가게입니다."));

        recompute(store, demo);
        // 갱신된 값을 그대로 읽어 보여주려면 먼저 반영돼야 한다.
        storeSizeStatusRepository.flush();

        int counted = reviewRepository
                .findByStore_StoreIdAndDemoAndBlindStatus(storeId, demo, BlindStatus.NORMAL)
                .size();

        List<RecomputeResultResponse.SizeResult> sizes =
                storeSizeStatusRepository.findByStore_StoreId(storeId).stream()
                        .sorted(Comparator.comparing(StoreSizeStatus::getSize))
                        .map(RecomputeResultResponse.SizeResult::from)
                        .toList();

        return new RecomputeResultResponse(storeId, store.getName(), counted, sizes);
    }

    /**
     * 가게의 리뷰를 전부 다시 세어 점수·건수·태그를 갈아끼운다.
     *
     * ⚠ store_size_status는 (가게, 크기)당 한 행뿐이라 시연용과 실제용을 함께 담지 못한다.
     * demo=true로 부르면 목 리뷰로 계산한 값이 실제 리뷰로 계산한 값을 덮어쓴다.
     * 지금은 실제 리뷰가 없어 문제가 없지만, 실제 리뷰가 쌓이기 시작하면
     * store_size_status에 is_demo를 두고 행을 나눠야 한다.
     *
     * 등록·삭제 양쪽에서 같은 함수를 부르는 게 핵심이다.
     * 더하고 빼는 방식이면 시간가중 점수가 원래대로 돌아오지 않아 오차가 쌓인다.
     */
    public void recompute(Store store) {
        // 리뷰 등록·삭제에서 부르는 길. 실제 리뷰만 센다.
        recompute(store, false);
    }

    /**
     * @param demo true면 시연용 목 리뷰(is_demo=true)로 집계한다
     */
    public void recompute(Store store, boolean demo) {
        // demo가 정한 쪽 리뷰만 센다. 가려진 리뷰는 어느 쪽이든 뺀다 —
        // 화면에서 감췄는데 점수에 남아 있으면 가린 의미가 없다.
        List<Review> reviews = reviewRepository.findByStore_StoreIdAndDemoAndBlindStatus(
                store.getStoreId(), demo, BlindStatus.NORMAL);
        Instant now = Instant.now();

        int totalPossible = 0;
        int totalImpossible = 0;
        Instant storeLastVerified = null;

        for (StoreSizeStatus status : storeSizeStatusRepository.findByStore_StoreId(store.getStoreId())) {
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

            totalPossible += possibleCount;
            totalImpossible += impossibleCount;
            if (lastVerified != null && (storeLastVerified == null || lastVerified.isAfter(storeLastVerified))) {
                storeLastVerified = lastVerified;
            }
        }

        store.applyRecomputed(totalPossible, totalImpossible, storeLastVerified);
        recomputeTags(store, reviews);
    }

    private void recomputeTags(Store store, List<Review> reviews) {
        Map<StoreTag, Integer> counts = new EnumMap<>(StoreTag.class);
        for (Review review : reviews) {
            for (StoreTag tag : review.getTags()) {
                counts.merge(tag, 1, Integer::sum);
            }
        }

        Map<StoreTag, StoreTagCount> existing = new EnumMap<>(StoreTag.class);
        storeTagCountRepository.findByStore_StoreIdOrderByCountDesc(store.getStoreId())
                .forEach(row -> existing.put(row.getTag(), row));

        counts.forEach((tag, count) -> {
            StoreTagCount row = existing.remove(tag);
            if (row == null) {
                row = storeTagCountRepository.save(StoreTagCount.builder()
                        .store(store)
                        .tag(tag)
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

    private StoreStatusSummary summarize(Store store) {
        List<SizeStatusResponse> sizeStatus =
                storeSizeStatusRepository.findByStore_StoreId(store.getStoreId()).stream()
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
