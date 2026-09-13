package com.server.walwang_be.service;

import com.server.walwang_be.dto.AdminUserDetail;
import com.server.walwang_be.dto.AdminUserListItem;
import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.AdminUserQueryRepository;
import com.server.walwang_be.repo.AdminUserQueryRepository.ReviewStat;
import com.server.walwang_be.repo.AdminUserQueryRepository.StampStat;
import com.server.walwang_be.repo.AdminUserQueryRepository.UserCount;
import com.server.walwang_be.repo.AdminUserQueryRepository.UserRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 관리자 페이지의 회원 조회. 조회 전용이다.
 *
 * 접속기록은 여기서 남기지 않는다 — 컨트롤러가 이 서비스를 부르기 전에 남긴다.
 * 서비스 안에서 기록하면 "기록 전에 데이터를 읽는" 순서가 생기고, 기록 실패와 조회 결과가 한 트랜잭션에 묶인다.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    static final int PAGE_SIZE = 20;
    static final int RECENT_LIMIT = 10;

    private final AdminUserQueryRepository adminUserQueryRepository;

    /** query가 빈 문자열이면 전체 목록. */
    @Transactional(readOnly = true)
    public Page<AdminUserListItem> listUsers(String query, int page) {
        PageRequest pageable = PageRequest.of(page, PAGE_SIZE);
        Page<UserRow> rows = query.isEmpty()
                ? adminUserQueryRepository.findUserRows(pageable)
                : adminUserQueryRepository.searchUserRows(likePattern(query), pageable);

        List<UUID> userIds = rows.getContent().stream().map(UserRow::getUserId).toList();
        Map<UUID, Long> reviewCounts = userIds.isEmpty()
                ? Map.of()
                : adminUserQueryRepository.countReviewsByUserIds(userIds).stream()
                .collect(Collectors.toMap(UserCount::getUserId, UserCount::getCount));

        return rows.map(row -> new AdminUserListItem(
                row.getUserId(),
                AdminViewFormats.maskEmail(row.getEmail()),
                row.getNickname(),
                AdminViewFormats.dateTime(row.getJoinedAt()),
                reviewCounts.getOrDefault(row.getUserId(), 0L)));
    }

    @Transactional(readOnly = true)
    public AdminUserDetail getUser(UUID userId) {
        User user = adminUserQueryRepository.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("회원을 찾을 수 없습니다."));

        List<ReviewStat> reviewStats = adminUserQueryRepository.reviewStats(userId);
        List<StampStat> stampStats = adminUserQueryRepository.stampStats(userId);

        return new AdminUserDetail(
                user.getUserId(),
                user.getEmail(),
                user.getNickname(),
                AdminViewFormats.dateTime(adminUserQueryRepository.findJoinedAt(userId)),
                reviewStats.stream().mapToLong(ReviewStat::getCount).sum(),
                reviewStats.stream()
                        .sorted(Comparator.comparing(ReviewStat::getDemo)
                                .thenComparing(ReviewStat::getBlindStatus))
                        .map(stat -> new AdminUserDetail.CountRow(
                                kindLabel(stat.getDemo()) + " · " + blindLabel(stat.getBlindStatus()),
                                stat.getCount()))
                        .toList(),
                adminUserQueryRepository.recentReviews(userId, Limit.of(RECENT_LIMIT)).stream()
                        .map(AdminUserService::toRecentReview)
                        .toList(),
                adminUserQueryRepository.countSavedStores(userId),
                adminUserQueryRepository.countSavedCourses(userId),
                adminUserQueryRepository.recentCourses(userId, Limit.of(RECENT_LIMIT)).stream()
                        .map(course -> new AdminUserDetail.CourseRow(
                                AdminViewFormats.dateTime(course.getCreatedAt()),
                                course.getTitle(),
                                kindLabel(course.getDemo())))
                        .toList(),
                stampStats.stream().mapToLong(StampStat::getCount).sum(),
                stampStats.stream()
                        .sorted(Comparator.comparing(StampStat::getStatus))
                        .map(stat -> new AdminUserDetail.CountRow(stampLabel(stat.getStatus()), stat.getCount()))
                        .toList());
    }

    /** 소문자로 바꾸고 LIKE 와일드카드를 이스케이프한다. 쿼리의 escape 문자는 '!'다. */
    static String likePattern(String query) {
        String escaped = query.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static AdminUserDetail.RecentReview toRecentReview(Review review) {
        String kind = kindLabel(review.isDemo()) + (review.isVerified() ? "" : " · 미인증");
        return new AdminUserDetail.RecentReview(
                AdminViewFormats.dateTime(review.getCreatedAt()),
                review.getStore().getName(),
                review.getDogSize().label(),
                review.isDogAllowed() ? "가능" : "불가",
                blindLabel(review.getBlindStatus()),
                kind);
    }

    private static String kindLabel(Boolean demo) {
        return Boolean.TRUE.equals(demo) ? "시연" : "실제";
    }

    private static String blindLabel(BlindStatus status) {
        return switch (status) {
            case NORMAL -> "정상";
            case BLINDED -> "블라인드";
            case DELETED -> "삭제 확정";
        };
    }

    private static String stampLabel(StampStatus status) {
        return switch (status) {
            case READY -> "실루엣 도장";
            case FALLBACK -> "기본 도장";
            case PENDING -> "생성 중";
        };
    }
}
