package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.scheduler.AdmissionState;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import com.weacsoft.aiApiCenter.app.service.BackendPoolService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /v1/**} 网关端到端测试。
 * <p>
 * 其余测试类验证调度决策，这里验证整条链路：
 * 真实 HTTP 请求 → 路由 → 鉴权 → 准入 → 转发到上游 → 响应回传。
 * <p>
 * 上游用进程内 {@link HttpServer} 模拟 vLLM，可控制其返回内容
 * （正常 JSON / SSE 流 / 500 / 慢响应），用于验证流式透传与错误透传。
 * <p>
 * 网关把 {@code /v1/<path>} 原样转发到上游的同一路径，因此各测试通过重设
 * {@code /v1/chat/completions} 的 context 来切换上游行为，不新建其他路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class OpenAiGatewayEndToEndTest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private BackendPoolService backendPoolService;

    /**
     * 准入状态是单例，测试之间必须显式重置，否则上一个用例残留的会话与槽位
     * 会让下一个用例拿到 503。
     */
    @Autowired
    private AdmissionState admissionState;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private HttpServer upstream;
    private final List<Long> createdKeyIds = new ArrayList<>();
    private final List<Long> createdBackendIds = new ArrayList<>();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 上游收到的最后一次请求，用于断言内容确实被转发 */
    private final AtomicReference<String> lastUpstreamBody = new AtomicReference<>();
    private final AtomicReference<String> lastUpstreamPath = new AtomicReference<>();
    private final AtomicReference<String> lastUpstreamSessionHeader = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws Exception {
        admissionState.reset();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newFixedThreadPool(4));
        upstreamReturnsJson();
        upstream.start();

        var pool = backendPoolService.create("e2e-gpu", "e2e",
                "http://127.0.0.1:" + upstream.getAddress().getPort(), 2, "/health");
        createdBackendIds.add(pool.getId());
    }

    // ---------------- 上游行为切换 ----------------

    /** 默认：返回一段 OpenAI 风格的 JSON。 */
    private void upstreamReturnsJson() {
        resetChatContext();
        upstream.createContext("/v1/chat/completions", exchange -> {
            lastUpstreamBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastUpstreamPath.set(exchange.getRequestURI().getPath());
            lastUpstreamSessionHeader.set(exchange.getRequestHeaders().getFirst("x-session-id"));
            byte[] body = "{\"id\":\"chatcmpl-test\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /** 返回 SSE 流（chunked，边发边 flush）。 */
    private void upstreamReturnsSse() {
        resetChatContext();
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);   // 0 = chunked，长度未知
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 3; i++) {
                    out.write(("data: {\"chunk\":" + i + "}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
    }

    /** 返回 500。 */
    private void upstreamReturns500() {
        resetChatContext();
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"upstream internal error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /** 慢响应：用于占用会话，制造在途请求。 */
    private void upstreamIsSlow(long millis) {
        resetChatContext();
        upstream.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(millis);
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (Exception ignored) {
            }
        });
    }

    @AfterEach
    void stopUpstream() {
        admissionState.reset();
        if (upstream != null) {
            upstream.stop(0);
        }
        createdKeyIds.forEach(id -> apiKeyService.delete(id));
        createdBackendIds.forEach(id -> backendPoolService.delete(id));
        createdKeyIds.clear();
        createdBackendIds.clear();
    }

    /**
     * 清除 /v1/chat/completions 的旧 context。
     * <p>
     * {@code HttpServer.removeContext} 对不存在的路径会抛
     * {@code IllegalArgumentException}，首次调用时该路径尚未注册，需忽略该异常。
     */
    private void resetChatContext() {
        try {
            upstream.removeContext("/v1/chat/completions");
        } catch (IllegalArgumentException ignored) {
            // 该路径尚不存在
        }
    }

    private ApiKeyService.CreatedKey newKey(String label) {
        ApiKeyService.CreatedKey created = apiKeyService.create(label, 5);
        createdKeyIds.add(created.key().getId());
        return created;
    }

    private String gatewayUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpResponse<String> post(String path, String apiKey, String sessionId, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (sessionId != null) {
            builder.header("x-session-id", sessionId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ==================== 正常转发 ====================

    @Test
    @DisplayName("合法 Key + session-id：转发到上游并原样返回结果")
    void forwardsToUpstreamAndReturnsBody() throws Exception {
        var key = newKey("e2e-正常");
        String payload = "{\"model\":\"qwen\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";

        HttpResponse<String> response =
                post("/v1/chat/completions", key.plaintext(), "e2e-sess-1", payload);

        assertThat(response.statusCode()).as("应转发成功").isEqualTo(200);
        assertThat(response.body()).as("应原样返回上游 body").contains("chatcmpl-test");
        assertThat(lastUpstreamPath.get()).as("请求应到达上游").isEqualTo("/v1/chat/completions");
        assertThat(lastUpstreamBody.get()).as("请求体应原样转发").isEqualTo(payload);
        assertThat(lastUpstreamSessionHeader.get()).as("session 头应透传给上游")
                .isEqualTo("e2e-sess-1");
    }

    @Test
    @DisplayName("SSE 流式响应可被完整透传（含 [DONE]）")
    void streamsSseResponseThrough() throws Exception {
        upstreamReturnsSse();
        var key = newKey("e2e-流式");

        HttpResponse<String> response =
                post("/v1/chat/completions", key.plaintext(), "e2e-sess-stream", "{\"stream\":true}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("SSE 的 Content-Type 应保留").contains("text/event-stream");
        assertThat(response.body())
                .as("三个 chunk 与结束标记都应到达客户端")
                .contains("chunk\":0").contains("chunk\":1").contains("chunk\":2").contains("[DONE]");
    }

    @Test
    @DisplayName("上游 5xx 原样透传")
    void passesUpstreamErrorStatusThrough() throws Exception {
        upstreamReturns500();
        var key = newKey("e2e-错误");

        HttpResponse<String> response =
                post("/v1/chat/completions", key.plaintext(), "e2e-sess-boom", "{}");

        assertThat(response.statusCode()).as("上游 500 应原样透传").isEqualTo(500);
        assertThat(response.body()).contains("upstream internal error");
    }

    // ==================== 鉴权与会话校验 ====================

    @Test
    @DisplayName("缺少 API Key 返回 401")
    void missingApiKeyReturns401() throws Exception {
        assertThat(post("/v1/chat/completions", null, "sess", "{}").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("无效 API Key 返回 401")
    void invalidApiKeyReturns401() throws Exception {
        assertThat(post("/v1/chat/completions", "sk-ac-not-a-real-key", "sess", "{}").statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("不带 x-session-id：有空槽时按普通网关转发（不再 400），且不把该头传给上游")
    void anonymousNoSessionIdForwardsWhenSlotFree() throws Exception {
        var key = newKey("e2e-匿名");
        String payload = "{\"model\":\"qwen\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HttpResponse<String> response = post("/v1/chat/completions", key.plaintext(), null, payload);

        assertThat(response.statusCode()).as("匿名请求有空槽应直接放行").isEqualTo(200);
        assertThat(response.body()).contains("chatcmpl-test");
        assertThat(lastUpstreamSessionHeader.get())
                .as("请求未携带 x-session-id，上游不应收到该头").isNull();
    }

    @Test
    @DisplayName("不带 x-session-id：槽位已满时返回 429（不进排队、无 Retry-After）")
    void anonymousNoSessionIdGets429WhenFull() throws Exception {
        upstreamIsSlow(1500);
        var key = newKey("e2e-匿名满");

        // 后端容量 2：用两个不同会话的慢请求占满全部槽位
        Thread h1 = new Thread(() -> {
            try { post("/v1/chat/completions", key.plaintext(), "fill-a", "{}"); }
            catch (Exception ignored) { }
        });
        Thread h2 = new Thread(() -> {
            try { post("/v1/chat/completions", key.plaintext(), "fill-b", "{}"); }
            catch (Exception ignored) { }
        });
        h1.start();
        h2.start();
        Thread.sleep(600);   // 等待两者都进入转发并占用槽位

        HttpResponse<String> response = post("/v1/chat/completions", key.plaintext(), null, "{}");

        assertThat(response.statusCode()).as("槽位满的匿名请求应快速失败 429").isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After"))
                .as("429 不引导排队重试").isNotPresent();
        assertThat(response.headers().firstValue("X-Queue-Reason"))
                .as("附带诊断原因").contains("backend_full");
        assertThat(response.body()).contains("rate_limited");

        h1.join(6000);
        h2.join(6000);
    }

    // ==================== 忙时 503 + Retry-After ====================

    @Test
    @DisplayName("同会话并发：后到者收到 503 + Retry-After（不是 429）")
    void concurrentSameSessionGets503WithRetryAfter() throws Exception {
        upstreamIsSlow(1200);
        var key = newKey("e2e-503");

        // 先发一个请求占用该会话（异步，不等待返回）
        Thread holder = new Thread(() -> {
            try {
                post("/v1/chat/completions", key.plaintext(), "e2e-busy-sess", "{}");
            } catch (Exception ignored) {
            }
        });
        holder.start();
        Thread.sleep(400);   // 等待其进入转发阶段

        HttpResponse<String> second =
                post("/v1/chat/completions", key.plaintext(), "e2e-busy-sess", "{}");

        assertThat(second.statusCode())
                .as("同会话并发应返回 503（文档 §9.2 第 2 条：不使用 429）")
                .isEqualTo(503);
        var retryAfterHeader = second.headers().firstValue("Retry-After");
        assertThat(retryAfterHeader)
                .as("应携带 Retry-After 头").isPresent();
        assertThat(Integer.parseInt(retryAfterHeader.orElse("0")))
                .as("Retry-After 应为正数秒").isGreaterThan(0);

        holder.join(6000);
    }

    @Test
    @DisplayName("不同会话可并行进入（会话锁只锁同一会话）")
    void differentSessionsCanRunConcurrently() throws Exception {
        upstreamIsSlow(600);
        var key = newKey("e2e-并行");

        // 两个不同 session 同时打，后端容量 2，两个都应放行
        Thread a = new Thread(() -> {
            try {
                post("/v1/chat/completions", key.plaintext(), "sess-A", "{}");
            } catch (Exception ignored) {
            }
        });
        a.start();
        Thread.sleep(200);
        HttpResponse<String> b = post("/v1/chat/completions", key.plaintext(), "sess-B", "{}");
        a.join(6000);

        assertThat(b.statusCode())
                .as("不同会话之间不应互相阻塞").isEqualTo(200);
    }
}
