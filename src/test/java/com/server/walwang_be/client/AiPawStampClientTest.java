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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 목 AI를 실제로 띄워 놓고 부른다.
 *
 * 여기서 확인하려는 것은 응답 처리보다 요청 모양이다 — AI 명세는
 * multipart/form-data 에 photo 파일 파트 하나를 요구하는데, 파트 이름이나
 * filename 이 빠지면 상대는 422 "Field required"를 준다.
 * 그 실수는 코드를 읽어서는 드러나지 않고 나가는 바이트에만 나타난다.
 */
class AiPawStampClientTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String responseContentType = "image/png";
    private volatile byte[] responseBody = PNG_MAGIC;

    @BeforeEach
    void startMockAi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try (InputStream in = exchange.getRequestBody()) {
                // multipart 본문을 그대로 문자열로 본다. 사진 바이트는 ASCII로 넣어 깨지지 않게 한다.
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
            exchange.getResponseHeaders().add("Content-Type", responseContentType);
            exchange.sendResponseHeaders(status, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        });
        server.start();
    }

    @AfterEach
    void stopMockAi() {
        server.stop(0);
    }

    private PawStampClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AiPawStampClient(new AiProperties(baseUrl, null, null, null, null));
    }

    @Test
    void 명세대로_photo_파일_파트_하나를_multipart로_보낸다() {
        client().createStamp("dog-photo-bytes".getBytes(StandardCharsets.UTF_8));

        assertThat(lastPath.get()).isEqualTo("/internal/segmentation/stamp");
        assertThat(lastContentType.get()).startsWith("multipart/form-data");
        // 파트 이름은 photo 여야 한다. 다르면 상대가 422 Field required 를 준다.
        assertThat(lastBody.get()).contains("name=\"photo\"");
        // filename 이 있어야 File 로 받는다. 없으면 문자열 폼 필드로 읽혀 역시 422 다.
        assertThat(lastBody.get()).contains("filename=");
        assertThat(lastBody.get()).contains("dog-photo-bytes");
    }

    @Test
    void 이미지가_오면_그_바이트를_그대로_돌려준다() {
        responseContentType = "image/png";
        responseBody = PNG_MAGIC;

        Optional<byte[]> stamp = client().createStamp("photo".getBytes(StandardCharsets.UTF_8));

        assertThat(stamp).isPresent();
        assertThat(stamp.get()).isEqualTo(PNG_MAGIC);
    }

    @Test
    void 폴백_JSON은_장애가_아니라_빈_결과다() {
        // 성공과 같은 200 이라 상태코드로는 못 가른다. Content-Type 으로 갈라야 한다.
        responseContentType = "application/json";
        responseBody = "{\"result\":\"FALLBACK_PAW\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(client().createStamp("photo".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void 요청이_명세와_어긋나_422가_오면_상태코드를_메시지에_남긴다() {
        // 타임아웃과 같은 문구로 뭉뚱그리면 우리 요청이 틀린 것을 알 수 없다.
        status = 422;
        responseContentType = "application/json";
        responseBody = "{\"loc\":[\"body\",\"photo\"],\"msg\":\"Field required\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client().createStamp("photo".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PawStampClient.StampUnavailableException.class)
                .hasMessageContaining("422");
    }

    @Test
    void AI가_500이어도_장애로_올린다() {
        status = 500;
        responseContentType = "application/json";
        responseBody = "{\"detail\":\"boom\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client().createStamp("photo".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PawStampClient.StampUnavailableException.class)
                .hasMessageContaining("500");
    }

    @Test
    void 연결하지_못하면_연결_실패로_구분된다() {
        String dead = "http://127.0.0.1:" + server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> new AiPawStampClient(new AiProperties(dead, null, null, null, null))
                .createStamp("photo".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PawStampClient.StampUnavailableException.class)
                .hasMessageContaining("연결하지 못했습니다");
    }
}
