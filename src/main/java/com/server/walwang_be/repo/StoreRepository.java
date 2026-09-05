package com.server.walwang_be.repo;

import com.server.walwang_be.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    /** 외부 API 재수집 시 이미 저장된 가게인지 판별한다. */
    Optional<Store> findByExternalId(String externalId);

    /**
     * 지도 중심에서 반경 안의 가게.
     *
     * geography 타입이라 radius 단위는 미터다(geometry였다면 도 단위가 되어 엉뚱한 결과가 난다).
     * ST_DWithin은 GiST 인덱스를 타므로 전체 스캔이 일어나지 않는다 —
     * stores.geog에 인덱스를 만들어 둬야 효과가 있다.
     */
    @Query(value = """
            SELECT * FROM stores s
            WHERE ST_DWithin(
                      s.geog,
                      ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                      :radius)
            """, nativeQuery = true)
    List<Store> findWithinRadius(@Param("lat") double lat,
                                 @Param("lng") double lng,
                                 @Param("radius") int radius);

    /** 실제 상호 부분 일치 검색. 실제 모드에서 쓴다. */
    List<Store> findByNameContainingIgnoreCase(String keyword);

    /**
     * 가명 부분 일치 검색. 시연 모드에서 쓴다.
     *
     * 화면에 나가는 이름으로 찾게 해서 검색어와 결과가 어긋나지 않게 한다.
     * display_name 이 비어 있는 가게는 LIKE 비교가 NULL 이라 결과에서 빠진다 —
     * 시연 대상 가게에 가명이 채워져 있어야 정상 동작한다.
     */
    List<Store> findByDisplayNameContainingIgnoreCase(String keyword);

    /**
     * 좌표 반경 안의 가게를 가까운 순으로. 거리(미터)를 함께 돌려준다.
     *
     * geography 타입이라 ST_Distance/ST_DWithin 모두 미터 단위다.
     * 후보가 여러 건일 수 있어 목록으로 받는다 — 몇 건이 걸리는지가
     * 그 반경을 믿어도 되는지 판단하는 근거가 된다.
     */
    @Query(value = """
            SELECT s.store_id    AS storeId,
                   s.external_id AS externalId,
                   s.name        AS name,
                   s.address  AS address,
                   ST_Distance(s.geog,
                       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance
            FROM stores s
            WHERE ST_DWithin(s.geog,
                      ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                      :radius)
            ORDER BY distance
            """, nativeQuery = true)
    List<NearbyStore> findNearbyOrderByDistance(@Param("lat") double lat,
                                                @Param("lng") double lng,
                                                @Param("radius") double radius);

    /** 좌표 검색 결과 한 건. */
    interface NearbyStore {
        UUID getStoreId();

        String getExternalId();

        String getName();

        String getAddress();

        double getDistance();
    }
}
