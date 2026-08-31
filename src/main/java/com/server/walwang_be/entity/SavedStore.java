package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 유저가 저장한 가게.
 * (user_id, store_id)가 PK라 같은 가게를 두 번 저장하는 일이 구조적으로 막힌다.
 */
@Entity
@Table(name = "saved_stores")
@IdClass(SavedStoreId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedStore {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 저장 목록을 최신순으로 보여주기 위해 둔다. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private SavedStore(User user, Store store) {
        this.user = user;
        this.store = store;
        this.createdAt = Instant.now();
    }
}
