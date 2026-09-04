package com.server.walwang_be.repo;

import com.server.walwang_be.entity.SavedCourse;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedCourseRepository extends JpaRepository<SavedCourse, UUID> {

    /**
     * 상세 조회용. 코스 하나를 그리려면 담긴 가게가 전부 필요하다.
     * 페이징이 없으니 컬렉션을 함께 끌어와도 안전하다.
     *
     * purposes는 여기 넣지 않는다. stores와 함께 넣으면 두 컬렉션을 한 SELECT에서
     * 조인하게 되어 행이 곱해지는데(stores × purposes), stores는 List라
     * 하이버네이트가 bag으로 다루어 중복을 걸러내지 않는다 —
     * 목적 2개짜리 3지점 코스가 가게 6개로 나온다.
     * 컬렉션 둘이 모두 List였다면 MultipleBagFetchException으로 떴겠지만,
     * purposes가 Set이라 예외 없이 조용히 늘어난다.
     * purposes는 EnumSet 몇 개라 지연 로딩으로 한 번 더 읽는 편이 싸다.
     */
    @EntityGraph(attributePaths = {"stores", "stores.store"})
    Optional<SavedCourse> findWithStoresByCourseId(UUID courseId);

    /**
     * 미리보기 목록. 카드에 가게 이름이 함께 나가므로 stores와 그 store까지 끌어온다 —
     * 안 그러면 코스 수만큼 추가 쿼리가 나간다(N+1).
     *
     * 페이징이 아니라 목록 전체를 주는 자리라 컬렉션을 함께 읽어도 안전하다.
     * 여권·저장 가게와 같은 demo 축으로 갈라 읽는다.
     */
    @EntityGraph(attributePaths = {"stores", "stores.store"})
    List<SavedCourse> findByUser_UserIdAndDemoOrderByCreatedAtDesc(UUID userId, boolean demo);

    /** 회원 탈퇴 시 코스도 함께 정리한다. */
    void deleteByUser_UserId(UUID userId);
}
