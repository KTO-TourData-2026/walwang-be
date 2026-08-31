package com.server.walwang_be.repo;

import com.server.walwang_be.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

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
}
