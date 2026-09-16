package com.server.walwang_be.service;

import com.server.walwang_be.dto.RecomputeResultResponse;
import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.AdminModerationQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 리뷰 임시조치. 되돌릴 수 있는 작업이지만 집계와 기록이 함께 움직여야 해서 순서가 중요하다.
 *
 * 트랜잭션이 하나로 묶이는지는 목으로 볼 수 없다(@Transactional은 프록시가 건다).
 * 여기서는 무엇을 어떤 순서로 부르는지와, 실행하지 않는 경우를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminModerationServiceTest {

    private static final UUID REVIEW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STORE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String OPERATOR = "alice";
    private static final String IP = "203.0.113.10";

    @Mock
    AdminModerationQueryRepository moderationQueryRepository;
    @Mock
    AccessLogService accessLogService;
    @Mock
    ReviewService reviewService;

    @InjectMocks
    AdminModerationService service;

    /** demo 축과 상태를 정해 리뷰 하나를 세운다. 엔티티에 setter가 없어 리플렉션으로 심는다. */
    private Review review(boolean demo, BlindStatus status) {
        Store store = Store.builder().name("테스트 카페").address("서울시 어딘가").lat(37.5).lng(127.0).build();
        setField(store, "storeId", STORE_ID);
        User author = User.builder().email("abcdef@example.com").password("hash").nickname("멍멍").build();
        setField(author, "userId", AUTHOR_ID);

        Review review = Review.builder()
                .user(author).store(store)
                .dogAllowed(true).dogSize(DogSize.SMALL_MEDIUM)
                .content("신고된 본문").demo(demo).verified(true)
                .build();
        setField(review, "reviewId", REVIEW_ID);
        if (status == BlindStatus.BLINDED) {
            review.blind();
        }
        given(moderationQueryRepository.findForModeration(REVIEW_ID)).willReturn(Optional.of(review));
        given(reviewService.recomputeStore(eq(STORE_ID), anyBoolean()))
                .willReturn(new RecomputeResultResponse(STORE_ID, "테스트 카페", 2, List.of()));
        return review;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 블라인드는_기록_상태변경_재집계_순서로_돈다() {
        Review review = review(false, BlindStatus.NORMAL);

        AdminModerationService.Applied applied =
                service.blind(REVIEW_ID, "약관 11조 — 욕설", OPERATOR, IP);

        assertThat(review.getBlindStatus()).isEqualTo(BlindStatus.BLINDED);
        assertThat(review.getBlindedAt()).isNotNull();
        assertThat(applied.countedReviews()).isEqualTo(2);

        InOrder order = inOrder(accessLogService, reviewService);
        order.verify(accessLogService).recordInCurrentTransaction(
                OPERATOR, IP, AccessAction.BLIND_REVIEW, AUTHOR_ID,
                "리뷰 " + REVIEW_ID + " 블라인드(약관 11조) — 사유: 약관 11조 — 욕설");
        order.verify(reviewService).recomputeStore(STORE_ID, false);
    }

    @Test
    void 해제는_UNBLIND로_기록하고_상태를_되돌린다() {
        Review review = review(false, BlindStatus.BLINDED);

        service.unblind(REVIEW_ID, "이의제기 수용", OPERATOR, IP);

        assertThat(review.getBlindStatus()).isEqualTo(BlindStatus.NORMAL);
        assertThat(review.getBlindedAt()).isNull();
        verify(accessLogService).recordInCurrentTransaction(
                eq(OPERATOR), eq(IP), eq(AccessAction.UNBLIND_REVIEW), eq(AUTHOR_ID), anyString());
        verify(reviewService).recomputeStore(STORE_ID, false);
    }

    /**
     * store_size_status는 is_demo로 행이 나뉘어 있고, 재집계는 부른 축의 행만 고친다.
     * 목 리뷰를 가린 뒤 demo=false로 부르면 시연 축이 그대로라 가린 리뷰가 시연 점수에 남는다.
     */
    @Test
    void 재집계는_리뷰의_demo_축으로_부른다() {
        review(true, BlindStatus.NORMAL);

        service.blind(REVIEW_ID, "약관 11조 위반", OPERATOR, IP);

        verify(reviewService).recomputeStore(STORE_ID, true);
        verify(reviewService, never()).recomputeStore(STORE_ID, false);
    }

    /** 조회 화면이 쓰는 record(REQUIRES_NEW)를 부르면 기록이 따로 커밋돼 원자성이 깨진다. */
    @Test
    void 따로_커밋되는_기록_메서드는_쓰지_않는다() {
        review(false, BlindStatus.NORMAL);

        service.blind(REVIEW_ID, "약관 11조 위반", OPERATOR, IP);

        verify(accessLogService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 이미_블라인드된_리뷰는_다시_가리지_않는다() {
        Review review = review(false, BlindStatus.BLINDED);
        var blindedAt = review.getBlindedAt();

        assertThatThrownBy(() -> service.blind(REVIEW_ID, "약관 11조 위반", OPERATOR, IP))
                .isInstanceOf(AdminModerationService.ModerationRejected.class)
                .hasMessageContaining("정상 노출 중인 리뷰만");

        // blinded_at 이 밀리면 30일 기한이 그만큼 늘어난다.
        assertThat(review.getBlindedAt()).isEqualTo(blindedAt);
        verifyNoInteractions(accessLogService, reviewService);
    }

    @Test
    void 가려지지_않은_리뷰는_해제하지_않는다() {
        review(false, BlindStatus.NORMAL);

        assertThatThrownBy(() -> service.unblind(REVIEW_ID, "이의제기 수용", OPERATOR, IP))
                .isInstanceOf(AdminModerationService.ModerationRejected.class)
                .hasMessageContaining("블라인드된 리뷰만");

        verifyNoInteractions(accessLogService, reviewService);
    }

    @Test
    void 사유가_비어_있으면_리뷰를_읽지도_않는다() {
        assertThatThrownBy(() -> service.blind(REVIEW_ID, "   ", OPERATOR, IP))
                .isInstanceOf(AdminModerationService.ModerationRejected.class)
                .hasMessageContaining("처리 사유를 입력하세요");

        verifyNoInteractions(moderationQueryRepository, accessLogService, reviewService);
    }

    @Test
    void 사유가_200자를_넘으면_실행하지_않는다() {
        String tooLong = "가".repeat(AdminModerationService.REASON_MAX + 1);

        assertThatThrownBy(() -> service.blind(REVIEW_ID, tooLong, OPERATOR, IP))
                .isInstanceOf(AdminModerationService.ModerationRejected.class)
                .hasMessageContaining("200자 이내");

        verifyNoInteractions(moderationQueryRepository, accessLogService, reviewService);
    }

    @Test
    void 없는_리뷰면_404로_끝난다() {
        given(moderationQueryRepository.findForModeration(REVIEW_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.blind(REVIEW_ID, "약관 11조 위반", OPERATOR, IP))
                .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(accessLogService, reviewService);
    }

    @Test
    void 사유의_앞뒤_공백은_다듬어_기록한다() {
        review(false, BlindStatus.NORMAL);

        service.blind(REVIEW_ID, "  약관 11조 위반  ", OPERATOR, IP);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(accessLogService).recordInCurrentTransaction(
                anyString(), anyString(), any(), any(), detail.capture());
        assertThat(detail.getValue()).endsWith("사유: 약관 11조 위반");
    }

    // ── 상세 조회 ───────────────────────────────────────────────

    @Test
    void 신고가_없는_리뷰의_본문은_열지_않는다() {
        review(false, BlindStatus.NORMAL);
        given(moderationQueryRepository.countReports(REVIEW_ID)).willReturn(0L);

        assertThatThrownBy(() -> service.getReportedReview(REVIEW_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("신고된 리뷰가 아닙니다");
    }

    @Test
    void 신고된_리뷰_상세는_본문과_작성자를_담는다() {
        review(false, BlindStatus.NORMAL);
        given(moderationQueryRepository.countReports(REVIEW_ID)).willReturn(2L);
        given(moderationQueryRepository.reasonsOf(REVIEW_ID)).willReturn(List.of());

        var detail = service.getReportedReview(REVIEW_ID);

        assertThat(detail.content()).isEqualTo("신고된 본문");
        assertThat(detail.nickname()).isEqualTo("멍멍");
        assertThat(detail.authorUserId()).isEqualTo(AUTHOR_ID);
        assertThat(detail.storeName()).isEqualTo("테스트 카페");
        assertThat(detail.blinded()).isFalse();
    }
}
