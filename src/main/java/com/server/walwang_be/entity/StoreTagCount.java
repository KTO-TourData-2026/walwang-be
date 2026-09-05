package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * 가게에 붙은 태그와 그 태그가 선택된 리뷰 수.
 *
 * 리뷰의 태그를 매번 집계하지 않으려고 두는 캐시 테이블이라,
 * 리뷰가 등록·삭제될 때 함께 갱신해야 한다.
 * 개수를 들고 있어서 review_tags처럼 @ElementCollection으로 줄이지 못했다.
 */
@Entity
@Table(name = "store_tags")
@IdClass(StoreTagCountId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreTagCount {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StoreTag tag;

    /**
     * 시연 집계인지. 목 리뷰로 센 값과 실제 리뷰로 센 값을 따로 담는다.
     * store_size_status와 같은 축이다 — 한 행만 두면 시연 재집계가 실제 태그를 덮어쓴다.
     */
    @Id
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** 이 태그가 선택된 리뷰 수. 상세 조회에서 많이 선택된 순으로 정렬한다. */
    @Column(name = "count", nullable = false)
    private int count;

    @Builder
    private StoreTagCount(Store store, StoreTag tag, boolean demo) {
        this.store = store;
        this.tag = tag;
        this.demo = demo;
    }

    /** 리뷰를 다시 집계한 개수로 갈아끼운다. */
    public void applyRecomputed(int count) {
        this.count = count;
    }
}
