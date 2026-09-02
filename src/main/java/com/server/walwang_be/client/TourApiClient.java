package com.server.walwang_be.client;

import com.server.walwang_be.config.TourApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털 반려동물 동반여행 API(15135102).
 *
 * 인증키에 들어 있는 '/'와 '+'가 이미 퍼센트 인코딩된 상태라,
 * UriComponentsBuilder에 .build(true)를 줘서 "이미 인코딩됨"을 알려야 한다.
 * 이걸 빠뜨리면 %2F가 %252F로 이중 인코딩돼
 * SERVICE_KEY_IS_NOT_REGISTERED_ERROR가 돌아온다.
 *
 * 응답 원본은 콘솔에만 찍고 어디에도 저장하지 않는다.
 * serviceKey는 어떤 경로로도 로그에 남기지 않는다 — 호출 URL 자체를 찍지 않는 이유다.
 */
@Slf4j
@Component
public class TourApiClient {

    private final TourApiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TourApiClient(TourApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeoutOrDefault().toMillis());
        factory.setReadTimeout((int) properties.timeoutOrDefault().toMillis());

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** 시군구 코드 목록. areaCode를 주면 그 시도의 시군구가 나온다. */
    public TourResponse areaCodes(String areaCode, int numOfRows) {
        UriComponentsBuilder builder = base("areaCode2")
                .queryParam("numOfRows", numOfRows)
                .queryParam("pageNo", 1);

        if (areaCode != null) {
            builder.queryParam("areaCode", areaCode);
        }
        return call("areaCode2", builder.build(true).toUri());
    }

    /**
     * 지역 기반 목록. 지역은 법정동 코드로 좁힌다.
     *
     * areaCode/sigunguCode를 쓰지 않는 이유는 그 값이 대부분 비어 있기 때문이다 —
     * 전국 9,685건 중 areaCode가 채워진 건 542건뿐이라, 그 필터로는 94%가 누락된다.
     * lDongRegnCd/lDongSignguCd는 정상적으로 채워져 있고 필터도 동작한다.
     * (서울 11 / 성동구 200 → 102건)
     *
     * @param contentTypeId null이면 전체 타입
     */
    public TourResponse areaBasedList(String lDongRegnCd, String lDongSignguCd,
                                      String contentTypeId, int numOfRows, int pageNo) {
        UriComponentsBuilder builder = base("areaBasedList2")
                .queryParam("numOfRows", numOfRows)
                .queryParam("pageNo", pageNo)
                // C: 수정일순. 페이지를 넘겨도 순서가 흔들리지 않게 고정한다.
                .queryParam("arrange", "C");

        if (lDongRegnCd != null) {
            builder.queryParam("lDongRegnCd", lDongRegnCd);
        }
        if (lDongSignguCd != null) {
            builder.queryParam("lDongSignguCd", lDongSignguCd);
        }
        if (contentTypeId != null) {
            builder.queryParam("contentTypeId", contentTypeId);
        }
        return call("areaBasedList2", builder.build(true).toUri());
    }

    /**
     * 서비스·오퍼레이션 이름을 직접 지정해 호출한다.
     * 어떤 경로가 살아 있는지 찾기 위한 임시 수단이라, 확인이 끝나면 지운다.
     */
    public TourResponse probe(String service, String operation, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://apis.data.go.kr/B551011/" + service + "/" + operation)
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", properties.mobileAppOrDefault())
                .queryParam("_type", "json");

        // build(true)는 "이미 인코딩된 값"으로 취급하므로 한글을 그대로 넣으면 거부된다.
        // serviceKey는 이미 인코딩된 상태라 손대지 않고, 넘겨받은 값만 인코딩한다.
        params.forEach((key, value) ->
                builder.queryParam(key, UriUtils.encode(value, StandardCharsets.UTF_8)));
        return call(service + "/" + operation, builder.build(true).toUri());
    }

    /** 공통 쿼리 파라미터. serviceKey는 이미 인코딩된 값으로 그대로 붙인다. */
    private UriComponentsBuilder base(String operation) {
        return UriComponentsBuilder
                .fromUriString(properties.baseUrlOrDefault() + "/" + operation)
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", properties.mobileAppOrDefault())
                .queryParam("_type", "json");
    }

    private TourResponse call(String operation, URI uri) {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "tour.service-key가 설정되지 않았습니다. application.yaml을 확인하세요.");
        }

        String raw;
        try {
            raw = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // 응답 본문에는 키가 없으므로 그대로 남겨도 된다. URL은 절대 찍지 않는다.
            log.warn("=== TOUR API ERROR [{}] status={} ===\n{}",
                    operation, e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("관광공사 API가 %s를 돌려줬습니다: %s"
                    .formatted(e.getStatusCode(), operation), e);
        } catch (RestClientException e) {
            // 연결 실패 등. 예외 메시지에 URL(=serviceKey)이 섞일 수 있어 종류만 남긴다.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.warn("=== TOUR API CALL FAILED [{}] {} ===", operation, root.getClass().getName());
            throw new IllegalStateException("관광공사 API 호출에 실패했습니다(%s): %s"
                    .formatted(root.getClass().getSimpleName(), operation), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("관광공사 API 응답이 비어 있습니다: " + operation);
        }
        // 응답 원본은 콘솔에만 남긴다. 파일·DB에 쓰지 않는다.
        log.info("=== TOUR API RAW [{}] ===\n{}", operation, raw);

        // 인증키 오류는 200 + XML 에러 본문으로 오기도 해서 JSON이 아닐 수 있다.
        if (!raw.trim().startsWith("{")) {
            throw new IllegalStateException(
                    "관광공사 API가 JSON이 아닌 응답을 돌려줬습니다(위 콘솔 원본 참고): " + operation);
        }
        return parse(raw, operation);
    }

    private TourResponse parse(String raw, String operation) {
        JsonNode root = objectMapper.readTree(raw).path("response");
        JsonNode header = root.path("header");

        String resultCode = header.path("resultCode").asString("");
        if (!resultCode.isEmpty() && !"0000".equals(resultCode)) {
            throw new IllegalStateException("관광공사 API 오류: %s %s (%s)".formatted(
                    resultCode, header.path("resultMsg").asString(""), operation));
        }

        JsonNode body = root.path("body");
        JsonNode item = body.path("items").path("item");

        List<Map<String, Object>> items = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(node -> items.add(toMap(node)));
        } else if (item.isObject()) {
            // 결과가 1건이면 배열이 아니라 객체로 온다.
            items.add(toMap(item));
        }
        return new TourResponse(body.path("totalCount").asInt(0), items);
    }

    private Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.propertyNames().forEach(name -> {
            JsonNode value = node.get(name);
            map.put(name, value.isNumber() ? value.numberValue() : value.asString(""));
        });
        return map;
    }

    /** 호출 결과. */
    public record TourResponse(int totalCount, List<Map<String, Object>> items) {
    }
}
