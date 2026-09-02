package com.server.walwang_be.repo;

import com.server.walwang_be.entity.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {

    /** 회원 탈퇴 시 함께 정리한다. */
    void deleteByUser_UserId(Long userId);
}
