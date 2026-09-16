package com.server.walwang_be.service;

import com.server.walwang_be.dto.AdminReportListItem;
import com.server.walwang_be.dto.AdminReportedReviewDetail;
import com.server.walwang_be.dto.RecomputeResultResponse;
import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.AdminModerationQueryRepository;
import com.server.walwang_be.repo.AdminModerationQueryRepository.ReasonCount;
import com.server.walwang_be.repo.AdminModerationQueryRepository.ReportedReviewRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 신고 조회와 리뷰 임시조치(약관 10조·11조, 정보통신망법 44조의2).
 *
 * <p><b>블라인드는 접속기록과 같은 트랜잭션에서 커밋한다.</b> 이용계약 해지와 같은 규칙이다
 * (AdminTerminationService 주석). 상태만 바뀌고 기록이 없으면 무엇을 근거로 가렸는지 답할 수 없고,
 * 기록만 남고 상태가 안 바뀌면 가려졌다고 적힌 리뷰가 화면에 그대로 보인다.
 *
 * <p><b>집계 재계산도 같은 트랜잭션에 넣는다.</b> blind_status 를 바꾸는 것은 리뷰 등록도 삭제도
 * 아니라서 store_size_status 가 저절로 다시 계산되지 않는다. 지금까지는 관리자가 런북을 보고
 * 재집계 API 를 손으로 불러야 했고, 빠뜨리면 목록에서 사라진 리뷰가 점수에는 그대로 남았다.
 * 여기서 함께 부르므로 빠뜨릴 자리가 없다.
 *
 * <p>재계산의 축(demo)은 리뷰 자신의 값을 쓴다. store_size_status 는 is_demo 로 행이 나뉘어 있고
 * 재집계는 부른 축의 행만 고친다. 목 리뷰를 가린 뒤 demo=false 로 부르면 시연 축은 그대로라
 * 가린 리뷰가 시연 점수에 계속 남는다.
 */
@Service
@RequiredArgsConstructor
public class AdminModerationService {

    static final int PAGE_SIZE = 20;

    /** 처리 사유 최대 길이. access_log.detail(500자)에 접두어와 함께 담긴다. */
    public static final int REASON_MAX = 200;

    /**
     * 임시조치 기간. 정보통신망법 44조의2가 정한 최장 기간이고 약관 11조 4항도 같다.
     *
     * 이 기간이 지나도 서버는 상태를 바꾸지 않는다 — 삭제 확정이냐 복원이냐는 사업자 판단이라
     * 스케줄러가 대신 정할 일이 아니다. 지난 건은 화면에서 강조하고 BlindExpiryScheduler 가 알린다.
     */
    public static final Duration TEMPORARY_MEASURE = Duration.ofDays(30);

    private static final String BLIND_DETAIL = "리뷰 %s 블라인드(약관 11조) — 사유: %s";
    private static final String UNBLIND_DETAIL = "리뷰 %s 블라인드 해제 — 사유: %s";

    private final AdminModerationQueryRepository moderationQueryRepository;
    private final AccessLogService accessLogService;
    private final ReviewService reviewService;

    @Transactional(readOnly = true)
    public Page<AdminReportListItem> listReports(int page) {
        Instant now = Instant.now();
        Page<ReportedReviewRow> rows =
                moderationQueryRepository.findReportedReviews(PageRequest.of(page, PAGE_SIZE));

        return rows.map(row -> new AdminReportListItem(
                row.getReviewId(),
                row.getReportCount(),
                AdminViewFormats.dateTime(row.getLastReportedAt()),
                statusLabel(row.getBlindStatus()),
                row.getBlindStatus() == BlindStatus.BLINDED,
                blindedDays(row.getBlindedAt(), now),
                overdue(row.getBlindedAt(), now),
                storeName(row.getStoreName(), row.getStoreDisplayName(), row.getDemo()),
                row.getNickname(),
                AdminViewFormats.dateTime(row.getCreatedAt()),
                kindLabel(row.getDemo()),
                reasonSummary(moderationQueryRepository.reasonsOf(row.getReviewId()))));
    }

    /**
     * 신고된 리뷰 상세. 본문과 사진이 들어 있다.
     *
     * 신고가 없는 리뷰는 열지 않는다 — 신고 처리라는 목적 밖에서 본문을 보는 경로를 만들지 않는다.
     */
    @Transactional(readOnly = true)
    public AdminReportedReviewDetail getReportedReview(UUID reviewId) {
        Review review = moderationQueryRepository.findForModeration(reviewId)
                .orElseThrow(() -> new NoSuchElementException("리뷰를 찾을 수 없습니다."));
        if (moderationQueryRepository.countReports(reviewId) == 0) {
            throw new NoSuchElementException("신고된 리뷰가 아닙니다.");
        }

        List<ReasonCount> reasons = moderationQueryRepository.reasonsOf(reviewId);
        Instant now = Instant.now();
        boolean demo = review.isDemo();

        return new AdminReportedReviewDetail(
                review.getReviewId(),
                review.getUser().getUserId(),
                review.getUser().getNickname(),
                review.getStore().displayName(demo),
                AdminViewFormats.dateTime(review.getCreatedAt()),
                review.getContent() == null ? "" : review.getContent(),
                review.getPhotoUrl() != null && !review.getPhotoUrl().isBlank(),
                review.getPhotoUrl() == null ? "" : review.getPhotoUrl(),
                review.getDogSize().label(),
                review.isDogAllowed() ? "가능" : "불가",
                kindLabel(demo) + (review.isVerified() ? "" : " · 미인증"),
                statusLabel(review.getBlindStatus()),
                review.getBlindStatus() == BlindStatus.BLINDED,
                review.getBlindedAt() == null ? "" : AdminViewFormats.dateTime(review.getBlindedAt()),
                blindedDays(review.getBlindedAt(), now),
                overdue(review.getBlindedAt(), now),
                reasons.stream().mapToLong(ReasonCount::getCount).sum(),
                reasons.stream()
                        .map(reason -> new AdminReportedReviewDetail.ReasonRow(
                                reason.getReason(),
                                reason.getCount(),
                                AdminViewFormats.dateTime(reason.getLastReportedAt())))
                        .toList());
    }

    /** 임시조치. 노출을 끊고 집계에서 뺀다. */
    @Transactional
    public Applied blind(UUID reviewId, String reason, String operator, String ip) {
        return apply(reviewId, reason, operator, ip, true);
    }

    /** 임시조치 해제. 노출과 점수가 함께 돌아온다. */
    @Transactional
    public Applied unblind(UUID reviewId, String reason, String operator, String ip) {
        return apply(reviewId, reason, operator, ip, false);
    }

    private Applied apply(UUID reviewId, String reason, String operator, String ip, boolean blind) {
        // DB를 읽기 전에 입력 모양만 본다. 형식이 틀린 요청으로는 리뷰 본문을 읽지 않는다.
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty()) {
            throw new ModerationRejected("처리 사유를 입력하세요. 아무것도 바꾸지 않았습니다.");
        }
        if (trimmed.length() > REASON_MAX) {
            throw new ModerationRejected(
                    "처리 사유는 " + REASON_MAX + "자 이내로 입력하세요. 아무것도 바꾸지 않았습니다.");
        }

        Review review = moderationQueryRepository.findForModeration(reviewId)
                .orElseThrow(() -> new NoSuchElementException("리뷰를 찾을 수 없습니다."));

        // 상태를 먼저 확인한다. 이미 가려진 리뷰에 다시 걸면 blinded_at 이 밀려 30일 기한이 늘어나고,
        // 가려지지 않은 리뷰를 해제하면 아무 일도 일어나지 않는데 기록만 남는다.
        if (blind && review.getBlindStatus() != BlindStatus.NORMAL) {
            throw new ModerationRejected(
                    "정상 노출 중인 리뷰만 블라인드할 수 있습니다. 아무것도 바꾸지 않았습니다.");
        }
        if (!blind && review.getBlindStatus() != BlindStatus.BLINDED) {
            throw new ModerationRejected(
                    "블라인드된 리뷰만 해제할 수 있습니다. 아무것도 바꾸지 않았습니다.");
        }

        // 같은 트랜잭션에 담는다. 아래가 실패하면 이 행도 함께 사라진다.
        accessLogService.recordInCurrentTransaction(
                operator, ip,
                blind ? AccessAction.BLIND_REVIEW : AccessAction.UNBLIND_REVIEW,
                review.getUser().getUserId(),
                (blind ? BLIND_DETAIL : UNBLIND_DETAIL).formatted(reviewId, trimmed));

        if (blind) {
            review.blind();
        } else {
            review.unblind();
        }

        // 리뷰의 축으로 다시 센다. 재집계 쿼리가 돌기 전에 위 변경이 flush 되므로
        // 가려진 리뷰는 이 시점에 이미 집계 대상에서 빠져 있다.
        Store store = review.getStore();
        boolean demo = review.isDemo();
        RecomputeResultResponse recomputed = reviewService.recomputeStore(store.getStoreId(), demo);

        return new Applied(reviewId, store.displayName(demo), recomputed.countedReviews());
    }

    /**
     * 처리 결과. 완료 메시지에 쓴다.
     *
     * countedReviews 를 함께 보여준다 — 집계에 실제로 쓰인 리뷰 수라, 가린 만큼 줄었는지
     * 관리자가 그 자리에서 확인할 수 있다(런북이 재집계 응답에서 확인하라고 했던 값이다).
     */
    public record Applied(UUID reviewId, String storeName, long countedReviews) {
    }

    /** 실행하지 않고 되돌려보낸 요청. 메시지는 코드에 있는 고정 문자열만 담는다. */
    public static class ModerationRejected extends RuntimeException {
        public ModerationRejected(String message) {
            super(message);
        }
    }

    /** 사유별 건수를 한 줄로. 사유는 신고자가 쓴 자유 문장이라 화면에서 이스케이프된다. */
    private static String reasonSummary(List<ReasonCount> reasons) {
        return reasons.stream()
                .map(reason -> reason.getCount() > 1
                        ? "%s (%d건)".formatted(reason.getReason(), reason.getCount())
                        : reason.getReason())
                .collect(Collectors.joining(", "));
    }

    private static String blindedDays(Instant blindedAt, Instant now) {
        if (blindedAt == null) {
            return "";
        }
        return Duration.between(blindedAt, now).toDays() + "일";
    }

    private static boolean overdue(Instant blindedAt, Instant now) {
        return blindedAt != null && blindedAt.plus(TEMPORARY_MEASURE).isBefore(now);
    }

    private static String storeName(String name, String displayName, Boolean demo) {
        if (!Boolean.TRUE.equals(demo)) {
            return name;
        }
        return (displayName != null && !displayName.isBlank()) ? displayName : Store.MASKED_NAME;
    }

    private static String kindLabel(Boolean demo) {
        return Boolean.TRUE.equals(demo) ? "시연" : "실제";
    }

    private static String statusLabel(BlindStatus status) {
        return switch (status) {
            case NORMAL -> "정상";
            case BLINDED -> "블라인드";
            case DELETED -> "삭제 확정";
        };
    }
}
