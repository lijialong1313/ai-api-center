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
 * 走 jaravel 自己的路由与 {@link Response} 契约——<b>不另起一套</b>。
 * 流式与普通响应共用同一个 {@code Response} 类型：
 * <ul>
 *   <li>成功且上游返回 SSE/流：{@code ResponseBuilder.stream(...)}，边产边写；</li>
 *   <li>鉴权失败、排队中、上游错误：{@code ResponseBuilder.error(...)}，普通 JSON。</li>
 * </ul>
 * 两者都是 {@code Response}，因此可以共存于同一个处理方法，不存在
 * 「一个方法只能返回一种形态」的限制。
 * <p>
 * 准入流程（文档 §4.2）：
 * <ol>
 *   <li>鉴权：{@code Authorization: Bearer <key>}，按 SHA-256 查表；</li>
 *   <li>会话：读 {@code x-session-id}，缺失直接 400（§9.2 第 7 条）；</li>
 *   <li>准入：{@link AdmissionScheduler#decide}；拒绝时
 *       <b>503 + Retry-After</b>（§4.5，明确不用 429）；</li>
 *   <li>转发：原样把请求体送给后端，响应逐块透传；</li>
 *   <li>释放：在流式写出结束后释放槽位（§9.2 第 6 条）。</li>
 * </ol>
 */
@Controller
public class OpenAiGatewayController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGatewayController.class);

    /** 转发超时：要大于「单次 Retry-After 上限 + 单次生成耗时」（客户端文档 §3.2） */
    private static final Duration FORWARD_TIMEOUT = Duration.ofMinutes(30);

    private final ApiKeyService apiKeyService;
    private final AdmissionScheduler scheduler;

    /** 复用客户端实例：内部带连接池 */
    private final HttpClient httpClient = HttpClient.newBuilder()
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
        AdmissionScheduler.Decision decision =
                scheduler.decide(requestId, sessionId, apiKey.getKeyHash());
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

            // 上游鉴权：用该后端自己的 Key，绝不转发客户端那把。
            //
            // 为什么必须换：客户端拿的是中间件颁发的 sk-ac-*，vLLM 不认识它，
            // 直接把客户端的 Authorization 转过去一定 401。
            //
            // 两个细节：
            //   1) 头名可配（默认 Authorization），因为少数网关用 api-key；
            //   2) 值是 Bearer 形式（与 OpenAI 兼容端点一致）。
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

        // 流式响应：边读边写，逐块 flush（§9.2 第 6 条的落点）。
        //
        // 关键：SSE 的结束判定<b>不能等上游 EOF</b>。实测（JDK HttpClient）：
        //   · HttpRequest.timeout 不会中断 body InputStream 上的阻塞 read；
        //   · HTTP/1.1 keep-alive 的上游发完 [DONE] 后连接照常被复用，
        //     等 EOF 会让这条转发线程挂到超时（30 分钟），期间会话/槽位一直被占，
        //     同会话的下一轮请求全部 503——真实 DSH 场景就是「第一轮回复正常、
        //     第二轮起永久 503」，且 503 会被 headless 当 fatal 退出。
        // 所以流式路径以协议终结标记 "data: [DONE]" 为准：转发它之后立刻关闭上游流并释放。
        // 非流式（JSON，有 Content-Length）读完自然结束，EOF 语义本来就成立。
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

        // SSE 额外关闭代理缓冲，否则 nginx 会把流攒起来一次性发
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
     * <b>必须用 {@link Request#rawBody()}，不能自己读 {@code getInputStream()}</b>：
     * {@code RequestFactory} 解析 JSON 时已经把 servlet 输入流读掉了，再读只会得到空内容。
     * 而且代理必须转发<b>原始字节</b>——用 {@code request.input()} 重新序列化会
     * 丢字段、改顺序、改变数字精度（对 LLM 请求尤其危险，token 计费依赖原样内容）。
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
     * 忙时响应：{@code 503 + Retry-After}。
     * <p>
     * 设计文档 §4.5 明确要求「不要用 429」：429 的语义是「配额用尽、别再来」，
     * 而这里是「排队中、稍后重试」，用 503 才能让客户端按 Retry-After 正常重试。
     */
    private Response busy(AdmissionScheduler.Decision decision) {
        var retryAfter = decision.retryAfterSeconds();
        if (retryAfter.isEmpty()) {
            // 终态（如 Key 停用）：403 而非 503，避免客户端无限重试放大负载
            return ResponseBuilder.error(403, decision.reason());
        }
        int seconds = retryAfter.get();
        ResponseBuilder.RawResponse raw = ResponseBuilder.raw()
                .status(503)
                .contentType("application/json;charset=utf-8")
                .header("Retry-After", String.valueOf(seconds))
                // HTTP 头只能是 ISO-8859-1；中文原因会让容器抛编码异常，
                // 把本该 503 的响应变成 500，客户端就不再按 Retry-After 重试了。
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
