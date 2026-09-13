package com.server.walwang_be.repo;

import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.entity.User;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 페이지 전용 조회. 앱이 쓰는 레포지토리와 섞지 않으려고 따로 둔다.
 *
 * 쓰기 메서드는 없다 — 관리자 페이지는 조회 전용이다.
 */
public interface AdminUserQueryRepository extends Repository<User, UUID> {

    /**
     * 회원 목록 한 줄.
     *
     * users에는 가입일 컬럼이 없다. 가입할 때 같은 트랜잭션에서 약관 동의가 저장되므로
     * 가장 이른 agreed_at을 가입일로 쓴다.
     */
    interface UserRow {
        UUID getUserId();

        String getEmail();

        String getNickname();

        Instant getJoinedAt();
    }

    interface UserCount {
        UUID getUserId();

        Long getCount();
    }

    interface ReviewStat {
        Boolean getDemo();

        BlindStatus getBlindStatus();

        Long getCount();
    }

    interface StampStat {
        StampStatus getStatus();

        Long getCount();
    }

    interface CourseRow {
        String getTitle();

        Boolean getDemo();

        Instant getCreatedAt();
    }

    @Query(value = """
            select u.userId as userId, u.email as email, u.nickname as nickname,
                   min(a.agreedAt) as joinedAt
              from User u left join UserAgreement a on a.user = u
             group by u.userId, u.email, u.nickname
             order by min(a.agreedAt) desc nulls last, u.userId
            """,
            countQuery = "select count(u) from User u")
    Page<UserRow> findUserRows(Pageable pageable);

    /**
     * 이메일·닉네임 부분 검색. pattern은 부르는 쪽이 소문자로 바꾸고 %, _ 를 ! 로 이스케이프해 넘긴다.
     */
    @Query(value = """
            select u.userId as userId, u.email as email, u.nickname as nickname,
                   min(a.agreedAt) as joinedAt
              from User u left join UserAgreement a on a.user = u
             where lower(u.email) like :pattern escape '!'
                or lower(u.nickname) like :pattern escape '!'
             group by u.userId, u.email, u.nickname
             order by min(a.agreedAt) desc nulls last, u.userId
            """,
            countQuery = """
            select count(u) from User u
             where lower(u.email) like :pattern escape '!'
                or lower(u.nickname) like :pattern escape '!'
            """)
    Page<UserRow> searchUserRows(@Param("pattern") String pattern, Pageable pageable);

    /** 목록 한 페이지분의 리뷰 수. 회원마다 따로 세면 N+1이 된다. */
    @Query("""
            select r.user.userId as userId, count(r) as count
              from Review r
             where r.user.userId in :userIds
             group by r.user.userId
            """)
    List<UserCount> countReviewsByUserIds(@Param("userIds") Collection<UUID> userIds);

    Optional<User> findByUserId(UUID userId);

    @Query("select min(a.agreedAt) from UserAgreement a where a.user.userId = :userId")
    Instant findJoinedAt(@Param("userId") UUID userId);

    @Query("""
            select r.demo as demo, r.blindStatus as blindStatus, count(r) as count
              from Review r
             where r.user.userId = :userId
             group by r.demo, r.blindStatus
            """)
    List<ReviewStat> reviewStats(@Param("userId") UUID userId);

    /** 최근 리뷰. 가게명만 쓰므로 store만 끌어온다. 본문·사진은 화면에 내지 않는다. */
    @Query("""
            select r from Review r join fetch r.store
             where r.user.userId = :userId
             order by r.createdAt desc
            """)
    List<Review> recentReviews(@Param("userId") UUID userId, Limit limit);

    @Query("select count(s) from SavedStore s where s.user.userId = :userId")
    long countSavedStores(@Param("userId") UUID userId);

    @Query("select count(c) from SavedCourse c where c.user.userId = :userId")
    long countSavedCourses(@Param("userId") UUID userId);

    @Query("""
            select c.title as title, c.demo as demo, c.createdAt as createdAt
              from SavedCourse c
             where c.user.userId = :userId
             order by c.createdAt desc
            """)
    List<CourseRow> recentCourses(@Param("userId") UUID userId, Limit limit);

    @Query("""
            select p.status as status, count(p) as count
              from Passport p
             where p.user.userId = :userId
             group by p.status
            """)
    List<StampStat> stampStats(@Param("userId") UUID userId);
}
