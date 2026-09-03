package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 탈퇴한 이메일의 흔적.
 *
 * 이메일 원문은 담지 않는다 — 탈퇴하면 지운다고 해놓고 주소를 그대로 들고 있으면
 * 지웠다고 할 수 없다. 같은 주소로 다시 오는지만 알면 되므로 해시로 충분하다.
 *
 * 해시에 페퍼를 섞는 이유는 이메일이 후보가 뻔한 값이기 때문이다.
 * 순수 SHA-256이면 흔한 주소를 미리 계산해 맞춰볼 수 있다.
 */
@Entity
@Table(name = "withdrawn_email")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnEmail {

    @Id
    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalReason reason;

    /** 이 시각이 지나면 흔적을 지운다. 보관 기간을 넘겨 들고 있지 않기 위해서다. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private WithdrawnEmail(String emailHash, WithdrawalReason reason, Instant expiresAt) {
        this.emailHash = emailHash;
        this.reason = reason;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }
}
