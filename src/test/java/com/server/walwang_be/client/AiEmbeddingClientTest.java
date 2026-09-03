package com.server.walwang_be.client;

import com.server.walwang_be.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 목 AI를 실제로 띄워 놓고 부른다.
 *
 * RestClient를 클라이언트가 직접 만들어 쥐고 있어 밖에서 갈아끼울 수 없다.
 * 그래서 스텁을 주입하는 대신 진짜 HTTP 서버를 세워 붙인다 —
 * 요청 본문이 규약대로 나가는지, 응답을 제대로 읽는지까지 함께 확인된다.
 */
class AiEmbeddingClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    private volatile int status = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startMockAi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopMockAi() {
        server.stop(0);
    }

    private EmbeddingClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AiEmbeddingClient(new AiProperties(baseUrl, null, null, null, null));
    }

    @Test
    void 태그를_보내고_1536차원_벡터를_받는다() {
        responseBody = embeddingJson(EmbeddingClient.DIMENSION);

        Optional<float[]> query = client().query(List.of("QUIET", "NOT_CROWDED"));

        assertThat(query).isPresent();
        assertThat(query.get()).hasSize(EmbeddingClient.DIMENSION);
        assertThat(query.get()[1]).isEqualTo(0.001f);
        assertThat(lastPath.get()).isEqualTo("/internal/embedding/query");
        // 프론트가 보낸 코드를 그대로 넘긴다.
        assertThat(lastBody.get()).isEqualTo("{\"tags\":[\"QUIET\",\"NOT_CROWDED\"]}");
    }

    @Test
    void 태그가_비면_아예_호출하지_않는다() {
        assertThat(client().query(List.of())).isEmpty();
        assertThat(client().query(null)).isEmpty();
        // 빈 문자열만 있는 것도 물어볼 것이 없는 것과 같다.
        assertThat(client().query(Arrays.asList("", "  ", null))).isEmpty();

        assertThat(calls.get()).isZero();
    }

    @Test
    void 차원이_어긋난_벡터는_버린다() {
        // 그대로 넘기면 pgvector가 질의에서 터진다. 없는 셈 치고 거리순으로 가야 한다.
        responseBody = embeddingJson(8);

        assertThat(client().query(List.of("QUIET"))).isEmpty();
    }

    @Test
    void AI가_400이면_장애로_올린다() {
        status = 400;
        responseBody = "{\"detail\":\"tags is empty\"}";

        assertThatThrownBy(() -> client().query(List.of("QUIET")))
                .isInstanceOf(EmbeddingClient.EmbeddingUnavailableException.class);
    }

    @Test
    void 갱신은_404를_따로_구분한다() {
        status = 404;

        assertThatThrownBy(() -> client().refresh(UUID.randomUUID()))
                .isInstanceOf(EmbeddingClient.EmbeddingStoreNotFoundException.class);
        assertThat(lastPath.get()).isEqualTo("/internal/embedding/refresh");
    }

    @Test
    void 갱신은_store_id를_문자열로_보낸다() {
        UUID storeId = UUID.randomUUID();
        responseBody = "{\"status\":\"ok\"}";

        client().refresh(storeId);

        assertThat(lastBody.get()).isEqualTo("{\"store_id\":\"" + storeId + "\"}");
    }

    private String embeddingJson(int dimension) {
        StringBuilder json = new StringBuilder("{\"embedding\":[");
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(i / 1000.0);
        }
        return json.append("]}").toString();
    }
}
