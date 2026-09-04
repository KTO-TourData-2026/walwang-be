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
 *
 * (user_id, store_id, is_demo)가 PK라 같은 가게를 두 번 저장하는 일이 구조적으로 막힌다.
 * is_demo가 PK에 들어가는 이유는 place_embedding과 같다 — 한 사람이 같은 가게를
 * 시연 쪽과 실제 쪽에 각각 저장할 수 있어야 한다. 빼두면 시연 중에 저장한 가게를
 * 평소 모드에서 다시 저장할 수 없고, 그 가게는 평소 목록에 영영 나타나지 않는다.
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

    /** 시연 중에 저장한 것인지. 조회는 항상 이 값으로 갈라진다. */
    @Id
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** 저장 목록을 최신순으로 보여주기 위해 둔다. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private SavedStore(User user, Store store, boolean demo) {
        this.user = user;
        this.store = store;
        this.demo = demo;
        this.createdAt = Instant.now();
    }
}
