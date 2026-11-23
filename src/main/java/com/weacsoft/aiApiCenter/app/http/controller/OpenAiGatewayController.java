package com.weacsoft.aiApiCenter.app.http.controller;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.aiApiCenter.app.scheduler.AdmissionScheduler;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import com.weacsoft.jaravel.vendor.http.controller.response.Response;
import com.weacsoft.jaravel.vendor.http.controller.response.ResponseBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * OpenAI 兼容网关 {@code /v1/**}：DSH 的唯一入口。
 * <p>
 * 走 jaravel 的路由与 {@link Response} 契约。流式与普通响应共用同一个 {@code Response} 类型：
 * <ul>
 *   <li>成功且上游返回 SSE/流：{@code ResponseBuilder.stream(...)}，边产边写；</li>
 *   <li>鉴权失败、排队中、上游错误：{@code ResponseBuilder.error(...)}，普通 JSON。</li>
 * </ul>
 * <p>
 * 准入流程（文档 §4.2）：
 * <ol>
 *   <li>鉴权：{@code Authorization: Bearer <key>}，按 SHA-256 查表；</li>
 *   <li>会话：读 {@code x-session-id}，缺失直接 400（§9.2 第 7 条）；</li>
 *   <li>准入：{@link AdmissionScheduler#decide}；拒绝时返回 503 + Retry-After（§4.5）；</li>
 *   <li>转发：请求体原样送给后端，响应逐块透传；</li>
 *   <li>释放：流式写出结束后释放槽位（§9.2 第 6 条）。</li>
 * </ol>
 */
@Controller
public class OpenAiGatewayController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGatewayController.class);

    /** 转发超时：要大于「单次 Retry-After 上限 + 单次生成耗时」（客户端文档 §3.2） */
    private static final Duration FORWARD_TIMEOUT = Duration.ofMinutes(30);

    private final ApiKeyService apiKeyService;
    private final AdmissionScheduler scheduler;

    /**
     * 转发用 HTTP 客户端。
     * <p>
     * 必须锁定 HTTP/1.1：JDK HttpClient 默认对明文 http 尝试 h2c 升级
     * （发出 {@code Upgrade: h2c} + {@code HTTP2-Settings} 头）。vLLM 前置的
     * uvicorn/starlette 对 h2c 升级支持不完整，表现为上游读不到请求体，
     * 返回 400 {@code {"detail":[{"loc":["body"],"msg":"Field required"}]}}。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public OpenAiGatewayController(ApiKeyService apiKeyService, AdmissionScheduler scheduler) {
        this.apiKeyService = apiKeyService;
        this.scheduler = scheduler;
    }

    /**
     * 统一入口：把 {@code /v1/**} 转发到后端的同名路径。
     *
     * @param request jaravel 请求
     * @return 响应（流式或 JSON）
     */
    public Response proxy(Request request) {
        // ---------- 1. 鉴权 ----------
        String plaintextKey = extractBearer(request);
        if (plaintextKey == null) {
            return ResponseBuilder.error(401, "缺少 Authorization: Bearer <api-key>");
        }
        ApiKey apiKey = apiKeyService.resolveUsable(plaintextKey);
        if (apiKey == null) {
            return ResponseBuilder.error(401, "API Key 无效或已停用");
        }

        // ---------- 2. 会话标识 ----------
        String sessionId = request.header("x-session-id");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseBuilder.error(400,
                    "缺少 x-session-id 请求头（DSH 需安装 dsh-session-gateway 插件）");
        }

        // ---------- 3. 准入 ----------
        String requestId = UUID.randomUUID().toString();
        // 带上 Key 等级：分级调度据此过滤后端
        AdmissionScheduler.Decision decision =
                scheduler.decide(requestId, sessionId, apiKey.getKeyHash(), apiKey.getLevel());
        if (!decision.admitted()) {
            return busy(decision);
        }

        BackendPool backend = decision.backend();
        try {
            apiKeyService.touch(apiKey);
        } catch (Exception e) {
            log.debug("[gateway] 更新 Key 使用时间失败（忽略）: {}", e.getMessage());
        }

        // ---------- 4/5. 转发 + 流式透传 ----------
        String path = request.path();
        String query = request.getRequest() == null ? null : request.getRequest().getQueryString();
        String targetUrl = trimTrailingSlash(backend.getBaseUrl()) + path
                + (query == null || query.isBlank() ? "" : "?" + query);

        byte[] body = readBody(request);
        HttpRequest upstreamRequest;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(FORWARD_TIMEOUT)
                    .header("Content-Type", contentTypeOf(request))
                    .header("Accept", acceptOf(request))
                    // 会话头透传给上游：便于后端日志串联
                    .header("x-session-id", sessionId)
                    .method(methodOf(request),
                            body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(body));

            // 上游鉴权：使用该后端自己的 Key。客户端提交的是中间件颁发的 sk-ac-*，
            // vLLM 不予识别，直接转发客户端的 Authorization 会得到 401。
            // 头名可配（默认 Authorization），值为 Bearer 形式。
            if (backend.hasApiKey()) {
                requestBuilder.header(backend.resolvedAuthHeader(),
                        "Bearer " + backend.getApiKey());
            }

            upstreamRequest = requestBuilder.build();
        } catch (Exception e) {
            scheduler.release(requestId);
            log.warn("[gateway] 构造上游请求失败 backend={} url={}: {}",
                    backend.getBackendId(), targetUrl, e.getMessage());
            return ResponseBuilder.error(502, "构造转发请求失败");
        }

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            scheduler.release(requestId);
            log.warn("[gateway] 转发失败 backend={} url={}: {}",
                    backend.getBackendId(), targetUrl, e.getMessage());
            return ResponseBuilder.error(502, "转发到后端失败：" + backend.getBackendId());
        }

        int status = upstreamResponse.statusCode();
        String contentType = upstreamResponse.headers().firstValue("Content-Type")
                .orElse("application/json");
        boolean sse = contentType.contains("text/event-stream");

        log.debug("[gateway] {} -> {} ({}) status={} sse={}",
                request.method(), targetUrl, backend.getBackendId(), status, sse);

        // 流式响应：边读边写，逐块 flush（对应设计文档 §9.2 第 6 条）。
        //
        // SSE 的结束判定以协议终结标记 data: [DONE] 为准，不以读取到流末尾为准：
        // HttpRequest.timeout 不中断 body InputStream 上的阻塞读取；HTTP/1.1 keep-alive
        // 下上游发送 [DONE] 后连接可能被复用而不返回 EOF。若仅依赖 EOF，转发线程会
        // 阻塞至超时，期间会话与槽位持续占用，同会话后续请求持续返回 503。
        // 非流式响应（JSON，含 Content-Length）读取到末尾即自然结束。
        final boolean sseFinal = sse;
        Response response = ResponseBuilder.stream(status, contentType, outputStream -> {
            try (InputStream in = upstreamResponse.body()) {
                byte[] buffer = new byte[4096];
                int read;
                // 跨 4096 边界扫描 [DONE]：终结标记可能被切开
                String tail = "";
                while ((read = in.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                    outputStream.flush();
                    if (sseFinal) {
                        String probe = tail + new String(buffer, 0, read,
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (probe.contains("data: [DONE]")) {
                            break;   // 生成结束的协议标记已转发，不必等 EOF
                        }
                        tail = probe.length() > 16 ? probe.substring(probe.length() - 16) : probe;
                    }
                }
            } finally {
                // 释放点：终结标记转发完 / EOF / 写失败（客户端断开）三种路径都到这里。
                // 不能提前到「收到响应头」——那时 vLLM 还在生成，单并发会被击穿。
                scheduler.release(requestId);
            }
        });

        // SSE 额外关闭代理缓冲：nginx 默认会缓冲响应流，攒满后一次性发送
        if (sse && response instanceof ResponseBuilder.StreamResponse streamResponse) {
            streamResponse.header("Cache-Control", "no-cache");
            streamResponse.header("X-Accel-Buffering", "no");
        }
        return response;
    }

    // ==================== 工具方法 ====================

    private String extractBearer(Request request) {
        String header = request.header("Authorization");
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = trimmed.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 取请求体原始字节。
     * <p>
     * 必须用 {@link Request#rawBody()}：{@code RequestFactory} 解析 JSON 时已经读完
     * servlet 输入流，再读只会得到空内容。代理必须转发原始字节——用
     * {@code request.input()} 重新序列化会丢字段、改变顺序与数字精度。
     *
     * @param request jaravel 请求
     * @return 原始字节；无 body 时返回空数组
     */
    private byte[] readBody(Request request) {
        byte[] cached = request.rawBody();
        return cached == null ? new byte[0] : cached;
    }

    private String methodOf(Request request) {
        String method = request.method();
        return method == null ? "POST" : method.toUpperCase();
    }

    private String contentTypeOf(Request request) {
        String contentType = request.contentType();
        return contentType == null ? "application/json" : contentType;
    }

    private String acceptOf(Request request) {
        String accept = request.header("Accept");
        return accept == null ? "application/json" : accept;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 忙时响应：{@code 503 + Retry-After}（文档 §4.5）。
     */
    private Response busy(AdmissionScheduler.Decision decision) {
        var retryAfter = decision.retryAfterSeconds();
        if (retryAfter.isEmpty()) {
            // 终态（如 Key 停用）：403，不引导客户端重试
            return ResponseBuilder.error(403, decision.reason());
        }
        int seconds = retryAfter.get();
        ResponseBuilder.RawResponse raw = ResponseBuilder.raw()
                .status(503)
                .contentType("application/json;charset=utf-8")
                .header("Retry-After", String.valueOf(seconds))
                // HTTP 头只能是 ISO-8859-1，中文原因会让容器抛编码异常，
                // 把 503 变成 500，客户端就不再按 Retry-After 重试。
                .header("X-Queue-Reason", asciiReason(decision.reason()));
        raw.body("{\"error\":{\"message\":\"排队中，请按 Retry-After 重试\","
                + "\"type\":\"queue_full\",\"retry_after\":" + seconds + "}}");
        return raw;
    }

    /** 把内部中文原因映射为 ASCII 标识，供 {@code X-Queue-Reason} 头使用 */
    private String asciiReason(String reason) {
        if (reason == null) {
            return "unknown";
        }
        if (reason.contains("会话")) {
            return "session_busy";
        }
        if (reason.contains("后端已满")) {
            return "backend_full";
        }
        if (reason.contains("其他 Key")) {
            return "weight_round_wait";
        }
        if (reason.contains("竞争")) {
            return "slot_race";
        }
        return "queued";
    }
}
