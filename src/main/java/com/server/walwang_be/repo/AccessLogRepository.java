package com.server.walwang_be.repo;

import com.server.walwang_be.entity.AccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    /* 접속기록 열람. 같은 시각이 겹쳐도 순서가 흔들리지 않게 log_id로 한 번 더 줄 세운다. */

    Page<AccessLog> findAllByOrderByAccessedAtDescLogIdDesc(Pageable pageable);

    Page<AccessLog> findByOperatorOrderByAccessedAtDescLogIdDesc(String operator, Pageable pageable);

    Page<AccessLog> findByTargetUserIdOrderByAccessedAtDescLogIdDesc(UUID targetUserId, Pageable pageable);

    Page<AccessLog> findByOperatorAndTargetUserIdOrderByAccessedAtDescLogIdDesc(
            String operator, UUID targetUserId, Pageable pageable);

    /**
     * 보관 기간이 끝난 행을 지운다.
     *
     * 기준 시각을 앱이 아니라 DB의 now()로 잡는다. 삭제를 막는 트리거도 DB 시계로 판단하므로,
     * 앱 서버 시계가 조금이라도 앞서 있으면 "아직 1년이 안 된 행"을 지우려다 트리거에 막혀
     * 정리 전체가 롤백된다. 간격도 트리거와 똑같이 적는다 — 한쪽만 바꾸면 같은 사고가 난다.
     *
     * 트랜잭션을 여기 건다. 부르는 쪽(스케줄러)이 프록시를 거치는지와 상관없이 걸리게 하려는 것이다.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM access_log WHERE accessed_at < now() - interval '1 year'",
            nativeQuery = true)
    int deleteExpired();
}
