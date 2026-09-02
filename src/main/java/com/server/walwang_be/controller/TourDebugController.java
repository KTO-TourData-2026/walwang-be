package com.server.walwang_be.controller;

import com.server.walwang_be.client.TourApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 관광공사 API 응답을 눈으로 확인하기 위한 임시 엔드포인트.
 *
 * local 프로파일에서만 뜬다 — 배포 환경에 노출되면 외부 API 응답이 그대로 새어 나간다.
 * 매칭 로직이 정해지면 이 컨트롤러는 지운다.
 */
@Profile("local")
@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class TourDebugController {

    private final TourApiClient tourApiClient;

    /** 시군구 코드 조회. 서울은 areaCode=1. */
    @GetMapping("/tour-area")
    public ResponseEntity<TourApiClient.TourResponse> areaCodes(
            @RequestParam(defaultValue = "1") String areaCode,
            @RequestParam(defaultValue = "50") int numOfRows) {
        return ResponseEntity.ok(tourApiClient.areaCodes(areaCode, numOfRows));
    }

    /** 지역 기반 목록 원본. */
    @GetMapping("/tour-raw")
    public ResponseEntity<TourApiClient.TourResponse> areaBasedList(
            @RequestParam(defaultValue = "1") String areaCode,
            @RequestParam(required = false) String sigunguCode,
            @RequestParam(required = false) String contentTypeId,
            @RequestParam(defaultValue = "1000") int numOfRows,
            @RequestParam(defaultValue = "1") int pageNo) {
        return ResponseEntity.ok(tourApiClient.areaBasedList(
                areaCode, sigunguCode, contentTypeId, numOfRows, pageNo));
    }

    /**
     * 서비스·오퍼레이션 이름을 바꿔가며 어떤 경로가 살아 있는지 찾는다.
     * 나머지 쿼리 파라미터는 그대로 전달된다.
     */
    @GetMapping("/tour-probe")
    public ResponseEntity<TourApiClient.TourResponse> probe(
            @RequestParam String service,
            @RequestParam String operation,
            @RequestParam Map<String, String> all) {
        Map<String, String> params = new LinkedHashMap<>(all);
        params.remove("service");
        params.remove("operation");
        return ResponseEntity.ok(tourApiClient.probe(service, operation, params));
    }

    /** 설정이 들어왔는지만 확인한다. 키 값은 절대 내보내지 않는다. */
    @GetMapping("/tour-config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(Map.of("serviceKeyConfigured", true));
    }
}
