package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * "user"는 PostgreSQL 예약어라 테이블명은 "users"로 둔다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    /** BCrypt 해시가 들어간다. 평문은 절대 저장하지 않는다. */
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true, length = 30)
    private String nickname;

    /**
     * 이메일 인증을 마친 시각. 값이 있으면 인증한 것이다(개인정보 처리방침: 인증 완료 사실과 일시).
     * 인증이 생기기 전에 가입한 회원은 비어 있다. 탈퇴하면 행과 함께 지워진다.
     */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Builder
    private User(String email, String password, String nickname, Instant emailVerifiedAt) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 인자는 반드시 인코딩된 해시여야 한다. */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
