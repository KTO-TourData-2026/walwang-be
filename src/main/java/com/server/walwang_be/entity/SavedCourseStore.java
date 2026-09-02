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

/**
 * 코스에 담긴 가게 한 곳과 그 방문 순서.
 *
 * (course_id, arrival_order)가 PK라 한 코스 안에서 순서가 겹치는 일이 구조적으로 막힌다.
 * 대신 같은 가게를 두 번 들르는 코스는 허용된다 — 왕복 산책이면 그럴 수 있다.
 */
@Entity
@Table(name = "saved_course_stores")
@IdClass(SavedCourseStoreId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedCourseStore {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SavedCourse course;

    /** 1부터 시작한다. */
    @Id
    @Column(name = "arrival_order", nullable = false)
    private int arrivalOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Builder
    private SavedCourseStore(SavedCourse course, int arrivalOrder, Store store) {
        this.course = course;
        this.arrivalOrder = arrivalOrder;
        this.store = store;
    }
}
