package com.server.walwang_be.repo;

import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.StampStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PassportRepository extends JpaRepository<Passport, UUID> {

    /**
     * 여권 목록. 시연/실제를 갈라 읽는다 —
     * 시연 모드를 끄면 시연 중에 찍힌 도장은 보이지 않아야 한다.
     */
    Page<Passport> findByUser_UserIdAndDemoOrderByCreatedAtDesc(
            UUID userId, boolean demo, Pageable pageable);

    /**
     * 마이 화면의 "모은 도장 수". 목록은 페이징이라 총 개수를 따로 센다.
     * 목록과 같은 축으로 세야 개수와 실제로 보이는 도장이 어긋나지 않는다.
     */
    long countByUser_UserIdAndDemo(UUID userId, boolean demo);

    /** 상세 화면에 리뷰 사진과 가게명이 함께 들어가므로 같이 끌어온다. */
    @EntityGraph(attributePaths = {"review", "store"})
    Optional<Passport> findWithReviewByPassportId(UUID passportId);

    /**
     * 도장 생성이 끝나지 않은 채 남은 여권.
     *
     * 비동기 작업은 서버가 내려가면 그대로 사라지므로, 오래 PENDING인 것들을
     * 주기적으로 다시 집어야 영영 빈 도장으로 남지 않는다.
     */
    List<Passport> findTop20ByStatusAndCreatedAtBefore(StampStatus status, Instant before);

    /** 리뷰가 지워지면 도장도 함께 사라진다. */
    void deleteByReview_ReviewId(UUID reviewId);

    /** 회원 탈퇴 시 함께 정리한다. */
    void deleteByUser_UserId(UUID userId);
}
