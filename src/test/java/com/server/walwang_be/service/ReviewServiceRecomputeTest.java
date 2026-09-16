package com.server.walwang_be.service;

import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.StoreSizeStatus;
import com.server.walwang_be.entity.StoreTag;
import com.server.walwang_be.entity.StoreTagCount;
import com.server.walwang_be.repo.ReviewRepository;
import com.server.walwang_be.repo.StoreSizeStatusRepository;
import com.server.walwang_be.repo.StoreTagCountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 리뷰가 모두 사라진 가게의 재집계.
 *
 * 일괄 재집계는 옛 값이 남은 가게를 대상에 넣고(ReviewRepository.findRecomputeTargets) 이 동작에 기댄다.
 * 행을 지우면 화면이 크기별 상태를 그리지 못하므로, 0으로 되돌리기만 해야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceRecomputeTest {

    private static final UUID STORE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    ReviewRepository reviewRepository;
    @Mock
    StoreSizeStatusRepository storeSizeStatusRepository;
    @Mock
    StoreTagCountRepository storeTagCountRepository;

    // 나머지 의존성은 recompute가 쓰지 않는다. 생성자 주입을 채우려고만 둔다.
    @Mock
    com.server.walwang_be.repo.ReviewReportRepository reviewReportRepository;
    @Mock
    com.server.walwang_be.repo.StoreRepository storeRepository;
    @Mock
    com.server.walwang_be.repo.UserRepository userRepository;
    @Mock
    com.server.walwang_be.repo.ReceiptTokenRepository receiptTokenRepository;
    @Mock
    com.server.walwang_be.client.SupabaseStorageClient storageClient;
    @Mock
    ImageProcessor imageProcessor;
    @Mock
    com.server.walwang_be.client.ReceiptOcrClient ocrClient;
    @Mock
    PassportService passportService;
    @Mock
    com.server.walwang_be.repo.PassportRepository passportRepository;
    @Mock
    EmbeddingService embeddingService;

    @InjectMocks
    ReviewService reviewService;

    private static Store store() {
        Store store = Store.builder().name("테스트 카페").address("서울시 어딘가").lat(37.5).lng(127.0).build();
        try {
            var field = Store.class.getDeclaredField("storeId");
            field.setAccessible(true);
            field.set(store, STORE_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return store;
    }

    @Test
    void 리뷰가_없으면_옛_점수와_태그를_0으로_되돌리고_행은_남긴다() {
        Store store = store();
        StoreSizeStatus small = StoreSizeStatus.builder().store(store).size(DogSize.SMALL_MEDIUM).demo(true).build();
        StoreSizeStatus large = StoreSizeStatus.builder().store(store).size(DogSize.LARGE).demo(true).build();
        // 심사 뒤 SQL로 지운 목 리뷰가 남긴 값이라고 친다.
        small.applyRecomputed(2.4, 0.9, 3, 1, Instant.parse("2026-09-01T00:00:00Z"));
        large.applyRecomputed(0.0, 1.8, 0, 2, Instant.parse("2026-09-02T00:00:00Z"));
        StoreTagCount tag = StoreTagCount.builder().store(store).tag(StoreTag.WIDE_YARD).demo(true).build();
        tag.applyRecomputed(4);

        given(reviewRepository.findByStore_StoreIdAndDemoAndVerifiedTrueAndBlindStatus(
                STORE_ID, true, BlindStatus.NORMAL)).willReturn(List.of());
        given(storeSizeStatusRepository.findByStore_StoreIdAndDemo(STORE_ID, true))
                .willReturn(List.of(small, large));
        given(storeTagCountRepository.findByStore_StoreIdAndDemoOrderByCountDesc(STORE_ID, true))
                .willReturn(List.of(tag));

        reviewService.recompute(store, true);

        for (StoreSizeStatus status : List.of(small, large)) {
            assertThat(status.getPossibleScore()).isZero();
            assertThat(status.getImpossibleScore()).isZero();
            assertThat(status.getPossibleCount()).isZero();
            assertThat(status.getImpossibleCount()).isZero();
            assertThat(status.getLastVerifiedAt()).isNull();
        }
        assertThat(tag.getCount()).isZero();

        verify(storeSizeStatusRepository, never()).delete(any());
        verify(storeSizeStatusRepository, never()).deleteAll(any());
        verify(storeSizeStatusRepository, never()).save(any());
        verify(storeTagCountRepository, never()).delete(any());
        verify(storeTagCountRepository, never()).deleteAll(any());
        verify(storeTagCountRepository, never()).save(any());
    }

    /** 다른 축의 행은 읽지도 않는다. 시연 리뷰를 지운 뒤 실제 점수가 흔들리지 않아야 한다. */
    @Test
    void 부른_축의_행만_건드린다() {
        Store store = store();
        given(reviewRepository.findByStore_StoreIdAndDemoAndVerifiedTrueAndBlindStatus(
                STORE_ID, true, BlindStatus.NORMAL)).willReturn(List.of());
        given(storeSizeStatusRepository.findByStore_StoreIdAndDemo(STORE_ID, true)).willReturn(List.of());
        given(storeTagCountRepository.findByStore_StoreIdAndDemoOrderByCountDesc(STORE_ID, true))
                .willReturn(List.of());

        reviewService.recompute(store, true);

        verify(storeSizeStatusRepository, never()).findByStore_StoreIdAndDemo(STORE_ID, false);
        verify(storeTagCountRepository, never()).findByStore_StoreIdAndDemoOrderByCountDesc(STORE_ID, false);
    }
}
