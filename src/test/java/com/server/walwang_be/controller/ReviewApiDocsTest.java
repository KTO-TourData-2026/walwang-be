package com.server.walwang_be.controller;

import com.server.walwang_be.client.TmapClient;
import com.server.walwang_be.service.NearbyPlaceFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /reviews 의 multipart 문서가 Swagger UI 에서 쓸 수 있는 모양인지 본다.
 *
 * data 파트는 JSON 이라 파트마다 Content-Type 이 따라붙어야 하는데, 그 값은 문서의
 * encoding 에만 적힌다. 이게 빠지면 Swagger UI 가 파트를 그냥 보내고 서버는 읽을
 * 컨버터를 못 찾아 415를 낸다 — 서버 코드는 멀쩡한데 UI 에서만 깨지는 종류라
 * 손으로 눌러 보기 전에는 모른다. 어노테이션 한 줄 지우면 조용히 사라지므로 여기서 붙든다.
 *
 * 스펙상 object 파트의 기본값이 application/json 이긴 하지만 UI 가 그 기본값을
 * 채워 주지는 않아서, 문서에 값이 있는지를 그대로 확인한다.
 *
 * 컨텍스트 설정을 SavedCourseDetailTest 와 똑같이 맞춰 두었다. 붙는 DB가 클라이언트를
 * 15개까지만 받아서, 설정이 다르면 컨텍스트가 하나 더 뜨고 커넥션 풀도 따로 생긴다.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.hikari.maximum-pool-size=2"
})
@AutoConfigureMockMvc
class ReviewApiDocsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TmapClient tmapClient;
    @MockitoBean private NearbyPlaceFinder nearbyPlaceFinder;

    private JsonNode multipartContent;

    @BeforeEach
    void setUp() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        multipartContent = objectMapper.readTree(body)
                .path("paths").path("/reviews").path("post")
                .path("requestBody").path("content").path("multipart/form-data");

        assertThat(multipartContent.isMissingNode())
                .as("POST /reviews 가 multipart/form-data 로 문서화돼야 한다 "
                        + "(consumes 가 빠지면 여기서부터 어긋난다)")
                .isFalse();
    }

    @Test
    void data_파트는_application_json_으로_문서화된다() {
        assertThat(multipartContent.path("encoding").path("data").path("contentType").asString())
                .isEqualTo("application/json");
    }

    /**
     * encoding 을 붙이는 데 쓴 @RequestBody 가 자동 생성 스키마를 덮어쓸 수 있다.
     * 덮어쓰면 Swagger UI 의 입력칸이 통째로 사라지므로 파트가 남아 있는지 같이 본다.
     */
    @Test
    void 파트_스키마는_그대로_남아_있다() {
        JsonNode properties = multipartContent.path("schema").path("properties");

        assertThat(properties.has("data")).as("data 파트").isTrue();
        assertThat(properties.path("photo").path("format").asString())
                .as("photo 는 파일 업로드 칸으로 나와야 한다")
                .isEqualTo("binary");
    }
}
