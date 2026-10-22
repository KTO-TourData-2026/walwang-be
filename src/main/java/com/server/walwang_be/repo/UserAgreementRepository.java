package com.server.walwang_be.repo;

import com.server.walwang_be.entity.TermCode;
import com.server.walwang_be.entity.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, UUID> {

    /** 회원 탈퇴 시 함께 정리한다. */
    void deleteByUser_UserId(UUID userId);

    /**
     * 해당 약관의 구버전에만 동의한 회원. 재동의를 받아야 하는 대상을 뽑는 용도다.
     * 현재 버전 동의 행이 이미 있으면 제외하므로, 재동의 이력이 쌓인 뒤에도 그대로 쓸 수 있다.
     */
    @Query("""
            select distinct a.user.userId
              from UserAgreement a
             where a.termCode = :termCode
               and a.termVersion <> :currentVersion
               and not exists (
                   select 1 from UserAgreement b
                    where b.user = a.user
                      and b.termCode = a.termCode
                      and b.termVersion = :currentVersion
               )
            """)
    List<UUID> findUserIdsAgreedToOutdatedVersion(@Param("termCode") TermCode termCode,
                                                  @Param("currentVersion") String currentVersion);

    /** 현재 버전은 enum이 들고 있으므로 약관 코드만 넘기면 된다. */
    default List<UUID> findUserIdsNeedingReconsent(TermCode termCode) {
        return findUserIdsAgreedToOutdatedVersion(termCode, termCode.version());
    }
}
