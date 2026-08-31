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

import java.time.Instant;

/**
 * 가게 × 반려견 크기별 동반 가능 상태.
 *
 * 등급(status) 컬럼은 두지 않는다. 점수만 저장하고 조회 시 계산하는데,
 * 그래야 임계값을 바꿨을 때 저장된 데이터를 손대지 않고 곧바로 반영된다.
 *
 * 가게를 수집할 때 크기당 1행씩(총 2행) 0으로 미리 만들어 둔다.
 * 그러면 리뷰가 없는 가게도 자동으로 UNKNOWN이 나온다.
 */
@Entity
@Table(name = "store_size_status")
@IdClass(StoreSizeStatusId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreSizeStatus {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DogSize size;

    /** 시간가중 합. 오래된 리뷰일수록 적게 반영한다. */
    @Column(name = "possible_score", nullable = false)
    private double possibleScore;

    @Column(name = "impossible_score", nullable = false)
    private double impossibleScore;

    /** 화면에 그대로 노출하는 원본 건수. score와 달리 가중치가 없다. */
    @Column(name = "possible_count", nullable = false)
    private int possibleCount;

    @Column(name = "impossible_count", nullable = false)
    private int impossibleCount;

    /** 마지막 리뷰 시각. 유저에게 "언제 확인된 정보인지" 보여주는 용도다. */
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    /** 이 행이 계산된 시각. 캐시 신선도 판단용이라 유저에게 보이지 않는다. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    private StoreSizeStatus(Store store, DogSize size) {
        this.store = store;
        this.size = size;
        this.updatedAt = Instant.now();
    }

    /**
     * 리뷰를 다시 집계한 결과로 갈아끼운다.
     *
     * 증감이 아니라 덮어쓰기인 이유는 리뷰 삭제 때문이다 — 시간가중 점수는
     * 더한 값을 그대로 빼도 원래대로 돌아오지 않아서, 지울 때마다 오차가 쌓인다.
     * 전체를 다시 세면 몇 번을 등록하고 지우든 같은 결과가 나온다.
     */
    public void applyRecomputed(double possibleScore, double impossibleScore,
                                int possibleCount, int impossibleCount,
                                Instant lastVerifiedAt) {
        this.possibleScore = possibleScore;
        this.impossibleScore = impossibleScore;
        this.possibleCount = possibleCount;
        this.impossibleCount = impossibleCount;
        this.lastVerifiedAt = lastVerifiedAt;
        this.updatedAt = Instant.now();
    }

    /** 저장된 값이 아니라 매번 계산한 등급. */
    public AvailabilityStatus status() {
        return AvailabilityStatus.of(possibleScore, impossibleScore);
    }
}
