package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 가게. 외부 API로 수집하므로 PK는 내부에서 만들고,
 * 외부 식별자는 external_id에 따로 둬 재수집 시 중복을 막는다.
 *
 * possible_count / impossible_count 는 store_size_status 합계의 캐시다.
 * 상세 조회에서 매번 집계하지 않으려고 두는 값이라, 리뷰가 반영될 때 함께 갱신해야 한다.
 */
@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    /** WGS84. geography 컬럼 정의와 반드시 같아야 한다. */
    public static final int SRID = 4326;

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long storeId;

    /** 외부 API의 식별자. 재수집할 때 이미 있는 가게인지 판별한다. */
    @Column(name = "external_id", nullable = false, unique = true, length = 64)
    private String externalId;

    @Column(nullable = false, length = 100)
    private String name;

    /** 지도 핀 위치. 반경 검색은 이 컬럼에 ST_DWithin을 건다. */
    @Column(columnDefinition = "geography(Point,4326)", nullable = false)
    private Point geog;

    /** 표시용 주소. 좌표와 별개로 화면에 그대로 보여준다. */
    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, length = 50)
    private String type;

    /** 외부 API에 영업시간이 없는 가게가 흔해 nullable로 둔다. */
    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(name = "possible_count", nullable = false)
    private int possibleCount;

    @Column(name = "impossible_count", nullable = false)
    private int impossibleCount;

    /** 마지막 리뷰 시각. 리뷰가 0건이면 값이 없다. */
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Builder
    private Store(String externalId, String name, double lat, double lng, String address,
                  String type, LocalTime openTime, LocalTime closeTime) {
        this.externalId = externalId;
        this.name = name;
        this.geog = toPoint(lat, lng);
        this.address = address;
        this.type = type;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }

    /**
     * JTS Coordinate는 (x, y) 순서라 경도가 먼저다.
     * 위도를 먼저 넣으면 조용히 엉뚱한 좌표가 되므로 반드시 이 메서드를 거친다.
     */
    public static Point toPoint(double lat, double lng) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
    }

    public double getLat() {
        return geog.getY();
    }

    public double getLng() {
        return geog.getX();
    }
}
