package com.server.walwang_be.controller;

import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.entity.CourseDuration;
import com.server.walwang_be.entity.CoursePurpose;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.entity.SavedCourse;
import com.server.walwang_be.entity.SavedCourseStore;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.SavedCourseRepository;
import com.server.walwang_be.repo.StoreRepository;
import com.server.walwang_be.service.NearbyPlaceFinder;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장 코스 상세에서 가게가 중복으로 나오던 문제를 막는 자리.
 *
 * 원인은 한 SELECT에서 컬렉션 둘(stores, purposes)을 조인해 행이 곱해진 것이었다.
 * stores는 List라 하이버네이트가 bag으로 다뤄 중복을 걸러내지 않으므로,
 * 목적이 2개면 3지점 코스의 가게가 6개로 나왔다.
 * 그래서 어느 확인이든 목적을 2개로 둔다 — 1개짜리는 곱해도 티가 나지 않아
 * 회귀가 그대로 지나간다.
 *
 * 컨트롤러부터 실제 DB까지 그대로 지나가고, 트랜잭션은 끝에서 되돌아간다
 * (확인용 코스와 유저가 DB에 남지 않는다).
 *
 * API 확인과 쿼리 확인을 한 클래스에 둔 이유는 컨텍스트를 하나만 띄우기 위해서다 —
 * 붙는 DB가 클라이언트 15개까지만 받아, 테스트 클래스마다 커넥션 풀을 따로 만들면
 * 스위트 전체를 돌릴 때 한도에 걸린다. 같은 이유로 풀도 작게 잡는다.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.hikari.maximum-pool-size=2"
})
@AutoConfigureMockMvc
@Transactional
class SavedCourseDetailTest {

    private static final int STORE_COUNT = 3;
    private static final double START_LAT = 37.5445;
    private static final double START_LNG = 127.0557;

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager em;
    @Autowired private SavedCourseRepository savedCourseRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private ObjectMapper objectMapper;

    /** 확인할 때마다 무료 호출 한도를 쓰지 않도록 세워 둔다. 경로선이 없어도 가게는 그대로 나온다. */
    @MockitoBean private TmapClient tmapClient;
    @MockitoBean private NearbyPlaceFinder nearbyPlaceFinder;

    private Statistics statistics;
    private UUID userId;
    private List<Store> stores;

    @BeforeEach
    void setUp() {
        given(tmapClient.walkRoute(any())).willReturn(Optional.empty());
        given(nearbyPlaceFinder.around(any())).willReturn(List.of());

        statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        stores = storeRepository.findAll(PageRequest.of(0, STORE_COUNT)).getContent();
        assumeTrue(stores.size() == STORE_COUNT, "가게가 " + STORE_COUNT + "곳 이상 있어야 확인할 수 있다");

        User user = User.builder()
                .email("course-dup-" + UUID.randomUUID() + "@example.com")
                .password("{noop}not-a-real-password")
                .nickname("t" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        em.persist(user);
        userId = user.getUserId();
    }

    // --- 응답으로 확인 -------------------------------------------------------

    /** 출발점 하나 + 가게 3곳. 가게가 곱해지면 여기서 드러난다. */
    @Test
    void 저장한_3지점_코스는_출발점과_가게_3개로_나온다() throws Exception {
        UUID courseId = saveCourseViaApi();

        JsonNode body = getJson("/courses/" + courseId + "?demo=true");

        assertThat(body.path("stores").size()).isEqualTo(STORE_COUNT + 1);
        assertThat(storeField(body, "arrivalOrder").map(Integer::parseInt).toList())
                .containsExactly(0, 1, 2, 3);
        assertThat(storeField(body, "storeId").distinct().count()).isEqualTo(STORE_COUNT + 1);
    }

    /**
     * 추천 응답을 그대로 돌려보낸 저장 요청(출발점 nil UUID 포함)이 받아지고,
     * 조회하면 저장한 좌표에 출발점이 다시 선다. null 필드도 키는 그대로 실린다.
     */
    @Test
    void 저장한_출발점이_조회_맨_앞에_나온다() throws Exception {
        UUID courseId = saveCourseViaApi();

        JsonNode start = getJson("/courses/" + courseId + "?demo=true").path("stores").get(0);

        assertThat(start.path("storeId").asString()).isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(start.path("name").asString()).isEqualTo("출발점");
        assertThat(start.path("lat").asDouble()).isEqualTo(START_LAT);
        assertThat(start.path("lng").asDouble()).isEqualTo(START_LNG);
        assertThat(start.has("type") && start.path("type").isNull()).isTrue();
        assertThat(start.has("address") && start.path("address").isNull()).isTrue();
        assertThat(start.has("status") && start.path("status").isNull()).isTrue();
    }

    /** 중복을 없애면서 지점별 상태가 빠지지 않았는지. */
    @Test
    void 지점마다_상태와_리뷰수가_그대로_실린다() throws Exception {
        UUID courseId = saveCourseViaApi();

        JsonNode body = getJson("/courses/" + courseId + "?demo=true");

        assertThat(body.path("stores").valueStream()
                .allMatch(store -> store.has("status") && store.has("reviewCount")))
                .isTrue();
        // purposes를 지연 로딩으로 돌렸으므로 그것도 그대로 나오는지 함께 본다.
        assertThat(body.path("purposes").size()).isEqualTo(2);
    }

    /** 목록도 같은 코스를 3지점으로 센다. */
    @Test
    void 저장_코스_목록도_3지점으로_센다() throws Exception {
        saveCourseViaApi();

        JsonNode course = getJson("/user/me/saved-courses?demo=true").path("courses").get(0);

        assertThat(course.path("storeCount").asInt()).isEqualTo(STORE_COUNT);
        assertThat(course.path("storeNames").size()).isEqualTo(STORE_COUNT);
    }

    // --- 쿼리로 확인 ---------------------------------------------------------

    @Test
    void 상세_쿼리는_지점당_한_건씩_순서대로_가져온다() {
        SavedCourse saved = givenCourse();

        List<SavedCourseStore> found = reload(saved.getCourseId()).getStores();

        assertThat(found).hasSize(STORE_COUNT);
        assertThat(found).extracting(SavedCourseStore::getArrivalOrder).containsExactly(1, 2, 3);
        assertThat(found).extracting(store -> store.getStore().getStoreId()).doesNotHaveDuplicates();
    }

    /**
     * 쿼리 수를 못 박아 둔다.
     *
     * 코스+가게를 한 번에 읽고(1), purposes를 지연 로딩으로 한 번 더 읽는다(1).
     * 가게 수가 늘어도 이 둘은 그대로다 — 늘어난다면 N+1로 돌아선 것이다.
     */
    @Test
    void 상세_조회는_가게_수와_무관하게_쿼리_두_번이다() {
        SavedCourse saved = givenCourse();
        em.flush();
        em.clear();

        statistics.clear();
        SavedCourse found = savedCourseRepository.findWithStoresByCourseId(saved.getCourseId())
                .orElseThrow();
        found.getStores().forEach(store -> store.getStore().getName());
        found.getPurposes().size();

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    /**
     * 목록은 컬렉션을 stores 하나만 끌어와 곱해질 짝이 없다.
     * 코스마다 가게를 다시 읽지도 않아 쿼리는 한 번이다.
     */
    @Test
    void 목록은_쿼리_한_번으로_중복_없이_가져온다() {
        givenCourse();
        em.flush();
        em.clear();

        statistics.clear();
        List<SavedCourse> courses =
                savedCourseRepository.findByUser_UserIdAndDemoOrderByCreatedAtDesc(userId, false);
        courses.forEach(course ->
                course.getStores().forEach(store -> store.getStore().getName()));

        assertThat(courses).hasSize(1);
        assertThat(courses.get(0).getStores()).hasSize(STORE_COUNT);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    // --- 준비 ---------------------------------------------------------------

    /**
     * 3지점, 목적 2개짜리 코스를 API로 저장한다.
     * 프론트처럼 추천 응답을 그대로 돌려보낸다 — storeIds 맨 앞에 출발점 nil UUID가 섞이고,
     * 출발 좌표는 stores[0]의 값이다.
     *
     * demo=true로 저장한다 — 조회도 같은 모드로 하지 않으면 코스가 없는 것으로 보인다.
     * 저장과 조회의 모드는 반드시 짝을 맞춘다.
     */
    private UUID saveCourseViaApi() throws Exception {
        String request = """
                {
                  "title": "중복 확인용 코스",
                  "dogSize": "SMALL_MEDIUM",
                  "purposes": ["CAFE", "WALK"],
                  "duration": "HALF_DAY",
                  "description": null,
                  "storeIds": ["00000000-0000-0000-0000-000000000000", %s],
                  "startLat": %s,
                  "startLng": %s
                }
                """.formatted(stores.stream()
                .map(store -> "\"" + store.getStoreId() + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow(), START_LAT, START_LNG);

        String response = mockMvc.perform(post("/courses?demo=true")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 방금 저장한 객체를 그대로 돌려받으면 조회 쿼리가 무엇을 가져오는지 볼 수 없다.
        em.flush();
        em.clear();

        return UUID.fromString(objectMapper.readTree(response).path("courseId").asString());
    }

    /** 같은 코스를 API를 거치지 않고 바로 심는다. 쿼리만 볼 때 쓴다. */
    private SavedCourse givenCourse() {
        SavedCourse course = SavedCourse.builder()
                .user(em.getReference(User.class, userId))
                .title("중복 확인용 코스")
                .dogSize(DogSize.SMALL_MEDIUM)
                .purposes(List.of(CoursePurpose.CAFE, CoursePurpose.WALK))
                .duration(CourseDuration.HALF_DAY)
                .description(null)
                .build();
        for (int i = 0; i < stores.size(); i++) {
            course.addStore(stores.get(i), i + 1);
        }
        em.persist(course);
        return course;
    }

    /** 저장한 뒤 1차 캐시를 비워 DB에서 실제로 읽어 온 모양을 본다. */
    private SavedCourse reload(UUID courseId) {
        em.flush();
        em.clear();
        return savedCourseRepository.findWithStoresByCourseId(courseId).orElseThrow();
    }

    private java.util.stream.Stream<String> storeField(JsonNode body, String field) {
        return body.path("stores").valueStream().map(store -> store.path(field).asString());
    }

    private JsonNode getJson(String url) throws Exception {
        String response = mockMvc.perform(get(url).with(authentication(auth())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** JwtAuthenticationFilter가 세우는 것과 같은 모양 — principal이 UUID다. */
    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
