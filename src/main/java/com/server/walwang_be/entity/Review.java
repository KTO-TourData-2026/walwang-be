package com.server.walwang_be.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 가게 방문 리뷰. 이 리뷰들이 쌓여 store_size_status의 점수가 된다.
 *
 * 태그에는 개수가 붙지 않으므로 별도 엔티티 대신 @ElementCollection으로 둔다 —
 * 리뷰가 지워지면 태그도 함께 사라져야 하는데, 이 매핑이 그걸 보장한다.
 */
@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "review_id")
    private UUID reviewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 이 크기의 개가 동반 가능했는지. 점수 계산의 방향을 정한다. */
    @Column(name = "dog_allowed", nullable = false)
    private boolean dogAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "dog_size", nullable = false, length = 20)
    private DogSize dogSize;

    /**
     * 사진은 선택이다 — 거절당한 리뷰는 찍을 사진 자체가 없다.
     * photoUrl은 EXIF를 지운 원본, thumbnailUrl은 목록용으로 줄인 사본이며 둘은 항상 함께 존재한다.
     */
    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(length = 1000)
    private String content;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "review_tags", joinColumns = @JoinColumn(name = "review_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "tag", nullable = false, length = 30)
    private Set<StoreTag> tags = EnumSet.noneOf(StoreTag.class);

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 시연용으로 심어둔 리뷰인지.
     * 조회는 항상 이 값으로 갈라진다 — 시연 모드는 목 리뷰만, 평소에는 실제 리뷰만 본다.
     */
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /**
     * 노출 상태. 신고가 들어와도 서버는 건드리지 않고 관리자가 DB에서 직접 바꾼다.
     * 읽는 쪽은 이 값을 존중해야 한다 — 가려둔 리뷰가 집계에 남으면 가린 의미가 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "blind_status", nullable = false, length = 20)
    private BlindStatus blindStatus;

    /** 가린 시각. 정상 상태면 비어 있다. 이의제기 기한을 세는 기준이 된다. */
    @Column(name = "blinded_at")
    private Instant blindedAt;

    @Builder
    private Review(User user, Store store, boolean dogAllowed, DogSize dogSize,
                   String photoUrl, String thumbnailUrl, String content,
                   Collection<StoreTag> tags, boolean demo) {
        this.user = user;
        this.store = store;
        this.dogAllowed = dogAllowed;
        this.dogSize = dogSize;
        this.photoUrl = photoUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.content = content;
        this.tags = (tags == null || tags.isEmpty())
                ? EnumSet.noneOf(StoreTag.class)
                : EnumSet.copyOf(tags);
        this.createdAt = Instant.now();
        this.demo = demo;
        this.blindStatus = BlindStatus.NORMAL;
    }
}
