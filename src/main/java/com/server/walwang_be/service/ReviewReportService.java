package com.server.walwang_be.service;

import com.server.walwang_be.dto.ReviewReportRequest;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.ReviewReport;
import com.server.walwang_be.repo.ReviewReportRepository;
import com.server.walwang_be.repo.ReviewRepository;
import com.server.walwang_be.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 리뷰 신고 접수. 약관 11조가 정한 절차의 앞단이다.
 *
 * 여기서 하는 일은 접수뿐이다 — 블라인드는 하지 않는다.
 * 신고 수만으로 자동으로 가리면 조직적 신고에 그대로 당한다.
 */
@Service
@RequiredArgsConstructor
public class ReviewReportService {

    private final ReviewRepository reviewRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final UserRepository userRepository;

    /**
     * 신고 접수.
     *
     * 이미 가려진 리뷰도 접수는 받는다 — 신고한 사람은 그 리뷰가 왜 아직 보이는지,
     * 혹은 이미 조치됐는지 알 수 없다. 문전에서 막으면 상태를 흘리는 셈이 된다.
     *
     * @throws NoSuchElementException 없는 리뷰 (404)
     * @throws IllegalArgumentException 본인 리뷰 (400)
     * @throws IllegalStateException 이미 신고함 (409)
     */
    @Transactional
    public UUID report(UUID reporterId, UUID reviewId, ReviewReportRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 리뷰입니다."));

        if (review.getUser().getUserId().equals(reporterId)) {
            throw new IllegalArgumentException("자신이 쓴 리뷰는 신고할 수 없습니다.");
        }
        if (reviewReportRepository.existsByReview_ReviewIdAndReporter_UserId(reviewId, reporterId)) {
            throw new IllegalStateException("이미 신고한 리뷰입니다.");
        }

        ReviewReport report = reviewReportRepository.save(ReviewReport.builder()
                .review(review)
                // 신고만 남길 것이라 User를 실제로 읽어올 필요가 없다.
                .reporter(userRepository.getReferenceById(reporterId))
                .reason(request.reason())
                .build());

        return report.getReportId();
    }
}
