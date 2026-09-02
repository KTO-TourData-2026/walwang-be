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
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 여권 도장. 동반 가능한 가게에 다녀왔다는 기록이다.
 *
 * 리뷰 하나당 최대 한 장이라 review_id에 unique를 건다 —
 * 재시도가 겹쳐도 도장이 두 장 찍히지 않는다.
 *
 * 리뷰가 지워지면 도장도 함께 사라진다. 다녀온 증거가 없어졌기 때문이다.
 */
@Entity
@Table(name = "passports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Passport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "passport_id")
    private Long passportId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false, unique = true)
    private Review review;

    /** 리뷰를 따라가면 알 수 있지만, 목록에서 가게명을 붙일 때 조인을 하나 줄여준다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 실루엣 도장 URL. 아직 만들어지지 않았으면 비어 있다. */
    @Column(name = "stamp_url", length = 500)
    private String stampUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StampStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private Passport(User user, Review review, Store store, StampStatus status, String stampUrl) {
        this.user = user;
        this.review = review;
        this.store = store;
        this.status = status;
        this.stampUrl = stampUrl;
        this.createdAt = Instant.now();
    }

    /** 세그멘테이션이 성공해 실루엣 도장이 생겼다. */
    public void applyStamp(String stampUrl) {
        this.stampUrl = stampUrl;
        this.status = StampStatus.READY;
    }

    /** 실루엣을 따지 못했다. 기본 발도장으로 확정한다. */
    public void applyFallback(String defaultStampUrl) {
        this.stampUrl = defaultStampUrl;
        this.status = StampStatus.FALLBACK;
    }
}
