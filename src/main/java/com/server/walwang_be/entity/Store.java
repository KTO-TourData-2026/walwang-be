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
import java.util.UUID;

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

    /**
     * 시연 모드에서 가명이 없을 때 내보낼 이름.
     *
     * 실제 상호로 떨어지지 않게 하려고 둔다. 자세히는 displayName(boolean) 참고.
     */
    public static final String MASKED_NAME = "비공개 가게";

    /**
     * 시연 모드에서 마스킹 주소가 없을 때 내보낼 값.
     *
     * 실제 주소로 떨어지지 않게 하려고 둔다. 자세히는 displayAddress(boolean) 참고.
     */
    public static final String MASKED_ADDRESS = "주소 비공개";

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "store_id")
    private UUID storeId;

    /** 외부 API의 식별자. 재수집할 때 이미 있는 가게인지 판별한다. */
    @Column(name = "external_id", nullable = false, unique = true, length = 64)
    private String externalId;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 시연용 가명. 목 리뷰를 붙인 채로 보여줄 때만 이 이름을 쓴다 —
     * 실제 가게에 없던 평판이 붙는 것이라 상호를 그대로 노출할 수 없다.
     * 내부 매칭·검색은 언제나 name을 쓴다.
     */
    @Column(name = "display_name", length = 100)
    private String displayName;

    /** 지도 핀 위치. 반경 검색은 이 컬럼에 ST_DWithin을 건다. */
    @Column(columnDefinition = "geography(Point,4326)", nullable = false)
    private Point geog;

    /** 표시용 주소. 좌표와 별개로 화면에 그대로 보여준다. */
    @Column(nullable = false, length = 255)
    private String address;

    /**
     * 시연용 주소. 상세 번지를 뭉갠 값이다("서울특별시 성동구 연무장길 일대").
     * 목 리뷰가 붙은 채로 번지까지 나가면 그 평판이 실제 가게에 그대로 붙는다.
     * 내부 매칭은 언제나 address 를 쓴다.
     */
    @Column(name = "display_address", length = 300)
    private String displayAddress;

    @Column(nullable = false, length = 50)
    private String type;

    /** 외부 API에 영업시간이 없는 가게가 흔해 nullable로 둔다. */
    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    // possible_count / impossible_count / last_verified_at 은 여기 두지 않는다.
    // store_size_status 를 크기별로 합치면 그대로 나오는 값이라, 따로 저장하면
    // 같은 수를 두 곳에 두게 되고 시연/실제 축이 생기면서 네 벌이 된다.
    // 상세 응답에서 크기별 행을 합쳐 만든다.

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

    /**
     * 화면에 내보낼 이름.
     *
     * 시연 모드에서 가명이 비어 있으면 실제 상호가 아니라 MASKED_NAME 을 준다.
     * 예전에는 실명으로 떨어졌는데, 그러면 가명을 넣지 않은 가게가 하나 생기는 순간
     * 그 가게만 조용히 실명으로 나간다 — 전 건을 채워 둔 동안에는 티가 나지 않다가
     * 가게가 추가될 때 터지는 종류라, 폴백 자체를 없앴다.
     *
     * 대조·검색은 이 값을 쓰지 않는다. 영수증 대조는 name 으로 하고(가명으로 맞춰보면
     * 무엇도 통과하지 못한다), 시연 검색은 display_name 컬럼을 직접 본다.
     */
    public String displayName(boolean demo) {
        if (!demo) {
            return name;
        }
        return (displayName != null && !displayName.isBlank()) ? displayName : MASKED_NAME;
    }

    /**
     * 화면에 내보낼 주소.
     *
     * 시연 모드에서 마스킹 주소가 비어 있으면 실제 주소가 아니라 MASKED_ADDRESS 를 준다.
     * displayName 과 같은 규칙이다 — 채우다 만 가게가 생겨도 번지가 새는 일은 없고,
     * 대신 "주소 비공개"가 눈에 띄어 빠진 곳을 알아챌 수 있다.
     */
    public String displayAddress(boolean demo) {
        if (!demo) {
            return address;
        }
        return (displayAddress != null && !displayAddress.isBlank())
                ? displayAddress
                : MASKED_ADDRESS;
    }

    public double getLat() {
        return geog.getY();
    }

    public double getLng() {
        return geog.getX();
    }
}
