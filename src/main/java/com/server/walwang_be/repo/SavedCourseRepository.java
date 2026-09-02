package com.server.walwang_be.repo;

import com.server.walwang_be.entity.SavedCourse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedCourseRepository extends JpaRepository<SavedCourse, UUID> {

    /**
     * 상세 조회용. 코스 하나를 그리려면 담긴 가게가 전부 필요하다.
     * 페이징이 없으니 컬렉션을 함께 끌어와도 안전하다.
     */
    @EntityGraph(attributePaths = {"purposes", "stores", "stores.store"})
    Optional<SavedCourse> findWithStoresByCourseId(UUID courseId);

    Page<SavedCourse> findByUser_UserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * 미리보기 목록. 카드에 가게 이름이 함께 나가므로 stores와 그 store까지 끌어온다 —
     * 안 그러면 코스 수만큼 추가 쿼리가 나간다(N+1).
     *
     * 페이징이 아니라 목록 전체를 주는 자리라 컬렉션을 함께 읽어도 안전하다.
     */
    @EntityGraph(attributePaths = {"stores", "stores.store"})
    List<SavedCourse> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);

    /** 회원 탈퇴 시 코스도 함께 정리한다. */
    void deleteByUser_UserId(UUID userId);
}
