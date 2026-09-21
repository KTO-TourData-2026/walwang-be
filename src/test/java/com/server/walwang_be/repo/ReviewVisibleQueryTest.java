package com.server.walwang_be.repo;

import com.server.walwang_be.entity.BlindStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 리뷰 조회 쿼리가 demo 축을 다시 가르지 않는지 본다.
 *
 * 실제로 리뷰를 넣고 확인하는 건 AdminPostgresTest 쪽이다(가게_리뷰_목록은_실제_리뷰와_시연_리뷰를_함께_보여준다).
 * 그 테스트는 PostGIS 가 필요해 평소 빌드에서는 건너뛴다 — 목 리뷰가 화면에서 사라지는 일을
 * 평소 빌드에서도 잡으려고 쿼리문 자체를 여기서 확인한다.
 *
 * 쿼리 문자열을 읽는 테스트라 리팩터링에 약하다. 대신 조건 하나(r.demo)만 보고,
 * 이름을 바꿔 조건을 되살리는 경우까지는 막지 못한다.
 */
class ReviewVisibleQueryTest {

    /** 조회는 축을 섞는다 — demo 조건이 돌아오면 시연용 목 리뷰가 실제 모드에서 사라진다. */
    @Test
    void 리뷰_조회_쿼리는_demo로_거르지_않는다() throws Exception {
        Method findVisible = ReviewRepository.class.getMethod(
                "findVisibleByStore", UUID.class, BlindStatus.class, UUID.class, Pageable.class);
        Query query = findVisible.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).doesNotContain("r.demo");
        assertThat(query.countQuery()).doesNotContain("r.demo");
        // 목록과 총 건수가 같은 리뷰를 세야 페이징이 어긋나지 않는다.
        assertThat(query.value()).contains("r.blindStatus = :blindStatus");
        assertThat(query.countQuery()).contains("r.blindStatus = :blindStatus");
        // 인증 조건은 그대로다 — 남이 시연 모드에서 쓴 리뷰는 계속 빠진다.
        assertThat(query.value()).contains("r.verified = true or r.user.userId = :viewerId");
        assertThat(query.countQuery()).contains("r.verified = true or r.user.userId = :viewerId");
    }

    /** 집계는 반대로 축을 가른 채 둔다 — 실제 점수가 목 리뷰로 움직이면 안 된다. */
    @Test
    void 집계_조회는_demo_축을_그대로_가른다() {
        assertThatCode(() -> ReviewRepository.class.getMethod(
                "findByStore_StoreIdAndDemoAndVerifiedTrueAndBlindStatus",
                UUID.class, boolean.class, BlindStatus.class))
                .doesNotThrowAnyException();
    }
}
