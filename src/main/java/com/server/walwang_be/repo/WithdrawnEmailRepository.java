package com.server.walwang_be.repo;

import com.server.walwang_be.entity.WithdrawalReason;
import com.server.walwang_be.entity.WithdrawnEmail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;

public interface WithdrawnEmailRepository extends JpaRepository<WithdrawnEmail, String> {

    /**
     * 아직 유효한 차단 기록이 있는지.
     *
     * expiresAt를 조건에 넣는 이유는 청소 스케줄러가 하루 한 번만 돌기 때문이다 —
     * 만료된 행이 잠시 남아 있어도 차단으로 이어지면 안 된다.
     */
    boolean existsByEmailHashAndReasonInAndExpiresAtAfter(
            String emailHash, Collection<WithdrawalReason> reasons, Instant now);

    /** 보관 기간이 끝난 행을 지운다. */
    long deleteByExpiresAtBefore(Instant now);
}
