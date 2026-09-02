package com.server.walwang_be.repo;

import com.server.walwang_be.entity.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, UUID> {

    /** 회원 탈퇴 시 함께 정리한다. */
    void deleteByUser_UserId(UUID userId);
}
