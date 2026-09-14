package com.server.walwang_be.repo;

import com.server.walwang_be.dto.AdminUserDetail;
import com.server.walwang_be.dto.AdminUserListItem;
import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.ReviewReport;
import com.server.walwang_be.entity.SavedCourse;
import com.server.walwang_be.entity.SavedStore;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.TermCode;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.entity.UserAgreement;
import com.server.walwang_be.repo.AdminModerationQueryRepository.ReasonCount;
import com.server.walwang_be.repo.AdminModerationQueryRepository.ReportedReviewRow;
import com.server.walwang_be.service.AccessLogService;
import com.server.walwang_be.service.AdminModerationService;
import com.server.walwang_be.service.AdminUserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * db/schema-access-log.sql 과 관리자 조회 쿼리를 실제 PostgreSQL(PostGIS)에서 돌린다.
 *
 * ⚠️ 이 테스트는 시작할 때 public 스키마를 통째로 지운다. 그래서 평소 테스트에서는 돌지 않고,
 * 반드시 버려도 되는 로컬 컨테이너에서만 돌린다.
 * 로컬이 평소 보는 개발 Supabase 프로젝트도 대상이 아니다 — 팀이 함께 쓰고, 운영에서 옮겨 온 데이터가 있을 수 있다.
 *
 *   docker run -d --name walwang-accesslog-verify -e POSTGRES_PASSWORD=verify \
 *       -p 127.0.0.1:55432:5432 postgis/postgis:16-3.4-alpine
 *   WALWANG_LOCAL_PG_URL=jdbc:postgresql://127.0.0.1:55432/postgres WALWANG_LOCAL_PG_PASSWORD=verify \
 *       ./gradlew test --tests '*AdminPostgresTest'
 *
 * URL이 localhost/127.0.0.1이 아니면 켜지지 않는다.
 *
 * 순서도 운영 배포와 같게 둔다: SQL 파일을 먼저 돌리고, 그 위에 ddl-auto: update로 앱이 뜬다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "WALWANG_LOCAL_PG_URL",
        matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.*")
@Import({AccessLogService.class, AdminUserService.class})
class AdminPostgresTest {

    private static String url() {
        return System.getenv("WALWANG_LOCAL_PG_URL");
    }

    private static String password() {
        String value = System.getenv("WALWANG_LOCAL_PG_PASSWORD");
        return value == null ? "" : value;
    }

    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AdminPostgresTest::url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", AdminPostgresTest::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @BeforeAll
    static void freshSchemaThenAccessLogSql() throws Exception {
        try (Connection connection = DriverManager.getConnection(url(), "postgres", password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            statement.execute(Files.readString(Path.of("src/main/resources/db/schema-access-log.sql")));
            // 한 번 더 돌려도 되는지(IF NOT EXISTS / DROP TRIGGER IF EXISTS)
            statement.execute(Files.readString(Path.of("src/main/resources/db/schema-access-log.sql")));
        }
    }

    @Autowired
    AccessLogService accessLogService;
    @Autowired
    AdminUserService adminUserService;
    @Autowired
    AdminModerationQueryRepository moderationQueryRepository;
    @Autowired
    AccessLogRepository accessLogRepository;
    @Autowired
    EntityManager entityManager;
    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void 붙은_DB는_로컬_검증용이다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.*");
        }
    }

    // ── 접속기록 테이블 ─────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 모든_수행업무_값이_CHECK_제약을_통과하고_ddl_update와_어긋나지_않는다() {
        UUID target = UUID.randomUUID();
        for (AccessAction action : AccessAction.values()) {
            accessLogService.record("check-enum", "203.0.113.10", action, target, "enum " + action);
        }

        assertThat(jdbc().queryForObject(
                "select count(*) from access_log where operator = 'check-enum' and target_user_id = ?",
                Long.class, target)).isEqualTo(AccessAction.values().length);
        // 앱이 SQL 파일의 테이블을 그대로 쓰는지(identity, timestamptz)
        assertThat(jdbc().queryForObject(
                "select data_type from information_schema.columns where table_name = 'access_log' and column_name = 'accessed_at'",
                String.class)).isEqualTo("timestamp with time zone");
        assertThat(jdbc().queryForObject(
                "select is_identity from information_schema.columns where table_name = 'access_log' and column_name = 'log_id'",
                String.class)).isEqualTo("YES");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 목록에_없는_수행업무는_DB가_거절한다() {
        assertThatThrownBy(() -> jdbc().update(
                "insert into access_log (operator, accessed_at, ip, action) values ('x', now(), '1.1.1.1', 'EXPORT')"))
                .hasMessageContaining("ck_access_log_action");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 접속기록은_수정할_수_없다() {
        accessLogService.record("tamper-update", "203.0.113.10", AccessAction.VIEW_USER, UUID.randomUUID(), "원본");

        assertThatThrownBy(() -> jdbc().update(
                "update access_log set operator = 'someone-else' where operator = 'tamper-update'"))
                .hasMessageContaining("수정할 수 없습니다");
        assertThat(jdbc().queryForObject(
                "select count(*) from access_log where operator = 'tamper-update'", Long.class)).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 일년이_안_된_기록은_지울_수_없고_TRUNCATE도_막힌다() {
        accessLogService.record("tamper-delete", "203.0.113.10", AccessAction.VIEW_USER, UUID.randomUUID(), "원본");
        jdbc().update("insert into access_log (operator, accessed_at, ip, action) "
                + "values ('tamper-delete', now() - interval '364 days', '203.0.113.10', 'LIST_USERS')");

        assertThatThrownBy(() -> jdbc().update("delete from access_log where operator = 'tamper-delete'"))
                .hasMessageContaining("보관 기간(1년)이 지난 행만");
        assertThatThrownBy(() -> jdbc().execute("truncate access_log"))
                .hasMessageContaining("TRUNCATE 할 수 없습니다");
        assertThat(jdbc().queryForObject(
                "select count(*) from access_log where operator = 'tamper-delete'", Long.class)).isEqualTo(2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 보관_기간_정리는_1년_지난_행만_지우고_트리거에_막히지_않는다() {
        jdbc().update("insert into access_log (operator, accessed_at, ip, action) "
                + "values ('retention-old', now() - interval '1 year 1 day', '203.0.113.10', 'LIST_USERS')");
        jdbc().update("insert into access_log (operator, accessed_at, ip, action) "
                + "values ('retention-recent', now() - interval '364 days', '203.0.113.10', 'LIST_USERS')");

        int removed = accessLogRepository.deleteExpired();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(jdbc().queryForObject(
                "select count(*) from access_log where operator = 'retention-old'", Long.class)).isZero();
        assertThat(jdbc().queryForObject(
                "select count(*) from access_log where operator = 'retention-recent'", Long.class)).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 접속기록_열람은_최신순이고_계정과_대상으로_거른다() {
        UUID target = UUID.randomUUID();
        accessLogService.record("reader-a", "203.0.113.10", AccessAction.VIEW_USER, target, "first");
        accessLogService.record("reader-b", "203.0.113.11", AccessAction.VIEW_USER, target, "second");
        accessLogService.record("reader-a", "203.0.113.10", AccessAction.LIST_USERS, null, "third");

        assertThat(accessLogService.find("", target, null, 0).getContent())
                .extracting(row -> row.detail()).containsExactly("second", "first");
        assertThat(accessLogService.find("reader-a", null, null, 0).getContent())
                .extracting(row -> row.detail()).containsExactly("third", "first");
        assertThat(accessLogService.find("reader-a", target, null, 0).getContent())
                .extracting(row -> row.detail()).containsExactly("first");
    }

    /** 수행업무 필터. Specification 으로 조립한 where 절이 실제 DB 에서 도는지 본다. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 접속기록_열람은_수행업무로도_거른다() {
        UUID target = UUID.randomUUID();
        accessLogService.record("filter-a", "203.0.113.10", AccessAction.VIEW_USER, target, "상세");
        accessLogService.record("filter-a", "203.0.113.10", AccessAction.BLIND_REVIEW, target, "블라인드");
        accessLogService.record("filter-b", "203.0.113.11", AccessAction.BLIND_REVIEW, null, "다른 계정 블라인드");

        assertThat(accessLogService.find("", target, AccessAction.BLIND_REVIEW, 0).getContent())
                .extracting(row -> row.detail()).containsExactly("블라인드");
        assertThat(accessLogService.find("filter-a", null, AccessAction.BLIND_REVIEW, 0).getContent())
                .extracting(row -> row.detail()).containsExactly("블라인드");
        assertThat(accessLogService.find("", null, AccessAction.BLIND_REVIEW, 0).getContent())
                .extracting(row -> row.detail())
                .containsExactly("다른 계정 블라인드", "블라인드");
        // 필터가 없으면 그대로 전체가 나온다.
        assertThat(accessLogService.find("filter-a", null, null, 0).getContent())
                .extracting(row -> row.detail()).containsExactly("블라인드", "상세");
    }

    // ── 관리자 조회 쿼리 ─────────────────────────────────────────

    @Test
    void 회원_목록_검색_상세가_실제_DB에서_돈다() throws Exception {
        Store store = persist(Store.builder().externalId("verify-store-1").name("테스트 카페")
                .lat(37.5665).lng(126.9780).address("서울 중구").type("카페").build());

        User alpha = persist(User.builder().email("alpha@example.com").password("x").nickname("알파").build());
        persist(UserAgreement.builder().user(alpha).termCode(TermCode.TERMS_OF_SERVICE).agreed(true).build());
        Thread.sleep(5);
        User beta = persist(User.builder().email("be_ta@example.com").password("x").nickname("베타").build());
        persist(UserAgreement.builder().user(beta).termCode(TermCode.TERMS_OF_SERVICE).agreed(true).build());
        User noAgreement = persist(User.builder().email("gamma@example.com").password("x").nickname("감마").build());

        Review real = persist(Review.builder().user(alpha).store(store).dogAllowed(true)
                .dogSize(DogSize.SMALL_MEDIUM).content("본문은 화면에 안 나간다").demo(false).verified(true).build());
        persist(Review.builder().user(alpha).store(store).dogAllowed(false)
                .dogSize(DogSize.LARGE).demo(true).verified(false).build());
        persist(Passport.builder().user(alpha).review(real).store(store)
                .status(StampStatus.READY).stampUrl("https://example.com/stamp.png").demo(false).build());
        persist(SavedStore.builder().user(alpha).store(store).demo(false).build());
        persist(SavedCourse.builder().user(alpha).title("주말 코스").dogSize(DogSize.SMALL_MEDIUM)
                .duration(CourseDuration.SHORT).demo(false).build());
        entityManager.flush();
        entityManager.clear();

        Page<AdminUserListItem> all = adminUserService.listUsers("", 0);
        assertThat(all.getContent()).extracting(AdminUserListItem::nickname)
                .containsExactly("베타", "알파", "감마");   // 가입일 최신순, 약관 동의가 없으면 맨 뒤
        AdminUserListItem alphaRow = all.getContent().get(1);
        assertThat(alphaRow.maskedEmail()).isEqualTo("al***@example.com");
        assertThat(alphaRow.reviewCount()).isEqualTo(2);
        assertThat(all.getContent().get(2).joinedAt()).isEqualTo("-");

        // '_'가 와일드카드로 먹으면 alpha@example.com 의 "ex"도 걸린다.
        assertThat(adminUserService.listUsers("e_", 0).getContent())
                .extracting(AdminUserListItem::nickname).containsExactly("베타");
        assertThat(adminUserService.listUsers("알파", 0).getContent())
                .extracting(AdminUserListItem::nickname).containsExactly("알파");
        assertThat(adminUserService.listUsers("GAMMA@", 0).getContent())
                .extracting(AdminUserListItem::nickname).containsExactly("감마");

        AdminUserDetail detail = adminUserService.getUser(alpha.getUserId());
        assertThat(detail.email()).isEqualTo("alpha@example.com");
        assertThat(detail.reviewTotal()).isEqualTo(2);
        assertThat(detail.reviewStats()).extracting(AdminUserDetail.CountRow::label)
                .containsExactly("실제 · 정상", "시연 · 정상");
        assertThat(detail.recentReviews()).hasSize(2)
                .allSatisfy(review -> assertThat(review.storeName()).isEqualTo("테스트 카페"));
        assertThat(detail.recentReviews()).extracting(AdminUserDetail.RecentReview::kind)
                .containsExactlyInAnyOrder("실제", "시연 · 미인증");
        assertThat(detail.savedStoreCount()).isEqualTo(1);
        assertThat(detail.savedCourseCount()).isEqualTo(1);
        assertThat(detail.recentCourses()).extracting(AdminUserDetail.CourseRow::title).containsExactly("주말 코스");
        assertThat(detail.stampTotal()).isEqualTo(1);
        assertThat(detail.stampStats()).extracting(AdminUserDetail.CountRow::label).containsExactly("실루엣 도장");

        AdminUserDetail empty = adminUserService.getUser(noAgreement.getUserId());
        assertThat(empty.reviewTotal()).isZero();
        assertThat(empty.recentReviews()).isEmpty();

        assertThatThrownBy(() -> adminUserService.getUser(UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * 신고 조회 쿼리. 그룹 집계와 countQuery 는 JPQL 이 파싱돼도 실행에서 어긋날 수 있어 여기서 돌려 본다.
     */
    @Test
    void 신고_목록과_사유_집계가_실제_DB에서_돈다() {
        Store store = persist(Store.builder().externalId("verify-store-report").name("신고 카페")
                .lat(37.5665).lng(126.9780).address("서울 중구").type("카페").build());
        User author = persist(User.builder().email("author@example.com").password("x").nickname("작성자").build());
        User reporterA = persist(User.builder().email("ra@example.com").password("x").nickname("신고A").build());
        User reporterB = persist(User.builder().email("rb@example.com").password("x").nickname("신고B").build());

        Review many = persist(Review.builder().user(author).store(store).dogAllowed(true)
                .dogSize(DogSize.SMALL_MEDIUM).content("신고 많은 본문").demo(false).verified(true).build());
        Review one = persist(Review.builder().user(author).store(store).dogAllowed(false)
                .dogSize(DogSize.LARGE).content("신고 하나").demo(true).verified(true).build());
        Review clean = persist(Review.builder().user(author).store(store).dogAllowed(true)
                .dogSize(DogSize.LARGE).content("신고 없음").demo(false).verified(true).build());

        persist(ReviewReport.builder().review(many).reporter(reporterA).reason("욕설").build());
        persist(ReviewReport.builder().review(many).reporter(reporterB).reason("욕설").build());
        persist(ReviewReport.builder().review(one).reporter(reporterA).reason("광고").build());
        entityManager.flush();
        entityManager.clear();

        // 건수 많은 순. 신고 없는 리뷰는 아예 나오지 않는다.
        Page<ReportedReviewRow> page = moderationQueryRepository.findReportedReviews(PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(ReportedReviewRow::getReviewId)
                .containsExactly(many.getReviewId(), one.getReviewId());

        ReportedReviewRow top = page.getContent().get(0);
        assertThat(top.getReportCount()).isEqualTo(2);
        assertThat(top.getNickname()).isEqualTo("작성자");
        assertThat(top.getStoreName()).isEqualTo("신고 카페");
        assertThat(top.getBlindStatus()).isEqualTo(BlindStatus.NORMAL);
        assertThat(top.getBlindedAt()).isNull();
        assertThat(top.getLastReportedAt()).isNotNull();
        assertThat(page.getContent().get(1).getDemo()).isTrue();

        // 같은 사유는 묶어서 센다.
        assertThat(moderationQueryRepository.reasonsOf(many.getReviewId()))
                .extracting(ReasonCount::getReason, ReasonCount::getCount)
                .containsExactly(tuple("욕설", 2L));
        assertThat(moderationQueryRepository.countReports(many.getReviewId())).isEqualTo(2);
        assertThat(moderationQueryRepository.countReports(clean.getReviewId())).isZero();

        // 상세·처리가 쓰는 조회는 가게와 작성자를 함께 들고 온다.
        Review loaded = moderationQueryRepository.findForModeration(many.getReviewId()).orElseThrow();
        assertThat(loaded.getStore().getName()).isEqualTo("신고 카페");
        assertThat(loaded.getUser().getNickname()).isEqualTo("작성자");

        // 임시조치 만료 조회. 30일 전에 가려진 것만 걸린다.
        loaded.blind();
        entityManager.flush();
        jdbc().update("update reviews set blinded_at = now() - interval '31 days' where review_id = ?",
                many.getReviewId());
        entityManager.clear();

        Instant threshold = Instant.now().minus(AdminModerationService.TEMPORARY_MEASURE);
        assertThat(moderationQueryRepository.findBlindedBefore(threshold))
                .containsExactly(many.getReviewId());
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
