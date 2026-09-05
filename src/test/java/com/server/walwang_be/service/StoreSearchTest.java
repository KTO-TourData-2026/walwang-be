package com.server.walwang_be.service;

import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 가게 검색이 모드에 맞는 컬럼으로 매칭·정렬하고 상한을 지키는지 본다.
 *
 * 실제 리포지토리로 돌려야 하는 이유가 분명하다 — Sort 에 넘긴 프로퍼티명은
 * 컨텍스트가 뜰 때가 아니라 쿼리를 만들 때 풀린다. 목으로 바꾸면 이름이 틀려도
 * 그대로 지나가고, 배포한 뒤 첫 검색에서 PropertyReferenceException 으로 터진다.
 * 그래서 여기서는 실제로 쿼리를 한 번 실행시키는 것 자체가 확인의 절반이다.
 *
 * 읽기만 한다. 붙는 DB가 로컬과 배포가 같은 곳이라 가게를 새로 넣지 않고,
 * 이미 있는 데이터에서 검색어를 뽑아 쓴다. 데이터가 모자라 확인이 성립하지 않으면
 * 실패가 아니라 건너뛴다(assumeTrue) — 없는 데이터를 두고 깨진 것처럼 보이면 곤란하다.
 *
 * 컨텍스트 설정을 SavedCourseDetailTest 와 똑같이 맞춰 두었다. 붙는 DB가 클라이언트를
 * 15개까지만 받아서, 설정이 조금이라도 다르면 컨텍스트가 하나 더 뜨고 커넥션 풀도
 * 따로 생긴다. 이 클래스만 보면 군더더기지만 스위트 전체로는 그게 아니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.hikari.maximum-pool-size=2"
})
@AutoConfigureMockMvc
@Transactional
class StoreSearchTest {

    /** StoreService.SEARCH_LIMIT 과 같아야 한다. private 이라 여기서 다시 적는다. */
    private static final int SEARCH_LIMIT = 50;

    /** 검색어를 뽑아낼 표본. 한 글자짜리 검색어가 상한을 넘길 만큼은 봐야 한다. */
    private static final int SAMPLE_SIZE = 500;

    @Autowired private StoreService storeService;
    @Autowired private StoreRepository storeRepository;

    @MockitoBean private TmapClient tmapClient;
    @MockitoBean private NearbyPlaceFinder nearbyPlaceFinder;

    // --- 실제 모드 ----------------------------------------------------------

    @Test
    void 실제모드_검색은_실명으로_매칭하고_실명순으로_50건까지_준다() {
        String keyword = keywordFrom(Store::getName);

        List<StoreCardResponse> result = storeService.search(keyword, false);

        // 여기까지 왔으면 Sort.by("name") 이 실제 컬럼으로 풀린 것이다.
        assertThat(result).hasSizeLessThanOrEqualTo(SEARCH_LIMIT);
        // 실제 모드에서는 응답의 이름이 곧 실명이라, 매칭이 name 으로 걸렸다는 뜻이 된다.
        assertThat(result).allSatisfy(card ->
                assertThat(card.storeName().toLowerCase()).contains(keyword.toLowerCase()));

        assertIsOrderedPrefix(result,
                storeRepository.findByNameContainingIgnoreCase(
                        keyword, PageRequest.of(0, SAMPLE_SIZE, StoreService.searchOrder("name"))));
        assertRepeatable(keyword, false);
    }

    // --- 시연 모드 ----------------------------------------------------------

    @Test
    void 시연모드_검색은_가명으로_매칭하고_가명순으로_50건까지_준다() {
        String keyword = keywordFrom(Store::getDisplayName);

        List<StoreCardResponse> result = storeService.search(keyword, true);

        // 여기까지 왔으면 Sort.by("displayName") 이 실제 컬럼으로 풀린 것이다.
        assertThat(result).hasSizeLessThanOrEqualTo(SEARCH_LIMIT);
        // 가명으로 걸린 가게만 나오므로 응답의 이름은 전부 가명이다.
        // 실명에만 검색어가 있는 가게가 섞여 있다면 이 확인에서 걸린다.
        assertThat(result).allSatisfy(card ->
                assertThat(card.storeName().toLowerCase()).contains(keyword.toLowerCase()));

        assertIsOrderedPrefix(result,
                storeRepository.findByDisplayNameContainingIgnoreCase(
                        keyword, PageRequest.of(0, SAMPLE_SIZE,
                                StoreService.searchOrder("displayName"))));
        assertRepeatable(keyword, true);
    }

    // --- 확인 도구 ----------------------------------------------------------

    /**
     * 잘라낸 50건이 정렬된 전체의 앞머리와 같은지 본다.
     *
     * 정렬을 빼고 상한만 걸면 어느 50건이 남을지가 요청마다 달라진다. 그래서
     * "정해진 순서의 앞에서부터 잘렸다"를 확인한다 — 이게 정렬을 넣은 이유 그대로다.
     *
     * 이름이 같은 가게가 실제로 있어서, 처음에는 이 확인이 동점 구간에서 깨졌다.
     * 정렬에 storeId 를 덧붙여 순서를 하나로 정한 뒤에야 통과한다.
     *
     * 이름이 가나다순인지를 자바에서 비교하지는 않는다. 정렬은 DB가 자기 콜레이션으로
     * 하는데 그 순서가 String.compareTo 와 같다는 보장이 없어서, 그렇게 확인하면
     * 코드가 멀쩡해도 깨질 수 있다. 대신 DB가 매긴 순서를 기준으로 삼는다.
     */
    private void assertIsOrderedPrefix(List<StoreCardResponse> result, List<Store> ordered) {
        assumeTrue(ordered.size() > SEARCH_LIMIT,
                "검색어에 걸리는 가게가 " + SEARCH_LIMIT + "곳을 넘어야 상한을 확인할 수 있다");

        assertThat(result).hasSize(SEARCH_LIMIT);
        assertThat(result.stream().map(StoreCardResponse::storeId).toList())
                .isEqualTo(ordered.stream().limit(SEARCH_LIMIT).map(Store::getStoreId).toList());
    }

    /** 같은 검색을 두 번 하면 같은 순서로 나와야 한다. 정렬이 없으면 여기서 흔들린다. */
    private void assertRepeatable(String keyword, boolean demo) {
        List<UUID> first = storeService.search(keyword, demo).stream()
                .map(StoreCardResponse::storeId).toList();
        List<UUID> second = storeService.search(keyword, demo).stream()
                .map(StoreCardResponse::storeId).toList();
        assertThat(first).isEqualTo(second);
    }

    /**
     * 있는 데이터에서 검색어를 뽑는다.
     *
     * 제보는 "스토어"였지만 그 낱말이 늘 그만큼 걸린다는 보장이 없다. 그래서 표본에서
     * 가장 흔한 글자를 검색어로 쓴다 — 아무 이름이나 집어 오면 드문 글자가 걸려
     * 상한 확인이 그냥 건너뛰어지고, 그러면 확인한 게 없는데 통과한 것처럼 보인다.
     */
    private String keywordFrom(Function<Store, String> nameOf) {
        List<String> names = storeRepository.findAll(PageRequest.of(0, SAMPLE_SIZE)).getContent()
                .stream()
                .map(nameOf)
                .filter(it -> it != null && !it.isBlank())
                .toList();
        assumeTrue(!names.isEmpty(), "이름이 채워진 가게가 있어야 검색을 확인할 수 있다");

        Map<String, Long> byChar = names.stream()
                .flatMap(name -> name.chars().mapToObj(c -> String.valueOf((char) c)))
                .filter(ch -> !ch.isBlank())
                .collect(Collectors.groupingBy(ch -> ch, Collectors.counting()));

        return byChar.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
    }
}
