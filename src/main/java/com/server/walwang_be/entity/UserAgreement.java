package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 약관 동의 이력. 동의 시점의 버전을 함께 남겨야
 * 나중에 약관이 개정됐을 때 누가 어느 버전에 동의했는지 알 수 있다.
 */
@Entity
@Table(name = "user_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "term_code", nullable = false, length = 40)
    private TermCode termCode;

    @Column(name = "term_version", nullable = false, length = 20)
    private String termVersion;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    @Builder
    private UserAgreement(User user, TermCode termCode, boolean agreed) {
        this.user = user;
        this.termCode = termCode;
        this.termVersion = termCode.version();
        this.agreed = agreed;
        this.agreedAt = Instant.now();
    }
}
