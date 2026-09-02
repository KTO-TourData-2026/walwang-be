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

    /** 가게명 부분 일치 검색. */
    List<Store> findByNameContainingIgnoreCase(String keyword);

    /** 이름만 뽑아온다. 외부 데이터와 대조할 때 전체를 메모리로 올리기 위한 것이다. */
    @Query("SELECT s.name FROM Store s")
    List<String> findAllNames();

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
