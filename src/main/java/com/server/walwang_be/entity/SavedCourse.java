package com.server.walwang_be.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 유저가 저장해 둔 코스.
 *
 * 추천 결과를 그대로 박제한다 — 나중에 가게 정보가 바뀌거나 사라져도
 * 저장 당시의 거리·시간·경로는 그대로 남아야 하기 때문이다.
 */
@Entity
@Table(name = "saved_courses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "course_id")
    private UUID courseId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "dog_size", nullable = false, length = 20)
    private DogSize dogSize;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "saved_course_purposes",
            joinColumns = @JoinColumn(name = "course_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private Set<CoursePurpose> purposes = EnumSet.noneOf(CoursePurpose.class);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseDuration duration;

    /** 미터. */
    @Column(name = "total_distance", nullable = false)
    private int totalDistance;

    /** 분. */
    @Column(name = "total_duration", nullable = false)
    private int totalDuration;

    /** [[위도, 경도], ...] 형태의 좌표 목록. 지도에 선을 그릴 때 쓴다. */
    @Convert(converter = WalkPathConverter.class)
    @Column(name = "walk_path", columnDefinition = "text")
    private List<List<Double>> walkPath = new ArrayList<>();

    @Column(length = 500)
    private String description;

    /**
     * 코스에 담긴 가게. 코스가 지워지면 함께 사라져야 하므로 cascade + orphanRemoval을 건다.
     * 방문 순서대로 꺼내야 화면에 그대로 그릴 수 있다.
     */
    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("arrivalOrder ASC")
    private List<SavedCourseStore> stores = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private SavedCourse(User user, String title, DogSize dogSize,
                        Collection<CoursePurpose> purposes, CourseDuration duration,
                        int totalDistance, int totalDuration,
                        List<List<Double>> walkPath, String description) {
        this.user = user;
        this.title = title;
        this.dogSize = dogSize;
        this.purposes = (purposes == null || purposes.isEmpty())
                ? EnumSet.noneOf(CoursePurpose.class)
                : EnumSet.copyOf(purposes);
        this.duration = duration;
        this.totalDistance = totalDistance;
        this.totalDuration = totalDuration;
        this.walkPath = walkPath == null ? new ArrayList<>() : new ArrayList<>(walkPath);
        this.description = description;
        this.createdAt = Instant.now();
    }

    /** 방문 순서는 넘겨받은 목록의 순서를 그대로 따른다. */
    public void addStore(Store store, int arrivalOrder) {
        stores.add(SavedCourseStore.builder()
                .course(this)
                .store(store)
                .arrivalOrder(arrivalOrder)
                .build());
    }
}
