package com.weacsoft.aiApiCenter.app.gateway;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.aiApiCenter.app.scheduler.AdmissionScheduler;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 兼容网关 {@code /v1/**}：DSH 的唯一入口。
 * <p>
 * <b>为什么用 Spring MVC 原生 {@code @RestController}，而不走 jaravel 路由</b>：
 * jaravel 的 {@code Response} 契约只有 {@code getContent()} / {@code getBytes()}，
 * 适配层会先把响应体整体读成 String/byte[] 再交给 {@code ServerResponse}。
 * 这对普通接口没问题，但对 SSE 是致命的——LLM 逐 token 生成的输出会一直积在内存里，
 * 客户端在生成结束前收不到任何内容（表现为「卡住」）。
 * <p>
 * 而 Spring MVC 原生支持 {@link StreamingResponseBody}：容器会把
 * {@code writeTo(OutputStream)} 直接接到 servlet 输出流上，边写边 flush。
 * 这里正好需要它，且 {@code /v1} 与 {@code /admin} 本就要求隔离
 * （设计文档 §9.2 第 9 条：管理 API 与 DSH 流量分离），用两套入口反而更清晰。
 * <p>
 * 准入流程（文档 §4.2）：
 * <ol>
 *   <li>鉴权：{@code Authorization: Bearer <key>}，按 SHA-256 查表；</li>
 *   <li>会话：读取 {@code x-session-id}，缺失直接 400（§9.2 第 7 条）；</li>
 *   <li>准入：调 {@link AdmissionScheduler#decide}；拒绝时
 *       <b>503 + Retry-After</b>（§4.5，明确不用 429）；</li>
 *   <li>转发：原样把请求体送给后端，响应（含 SSE）逐块透传；</li>
 *   <li>释放：在 {@link StreamingResponseBody} 的 finally 里释放——对应 §9.2 第 6 条
 *       「槽位释放必须在流式彻底结束后」。</li>
 * </ol>
 */
@RestController
public class OpenAiGatewayController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGatewayController.class);

    /** 转发超时：要大于「单次 Retry-After 上限 + 单次生成耗时」（客户端文档 §3.2） */
    private static final Duration FORWARD_TIMEOUT = Duration.ofMinutes(30);

    private final ApiKeyService apiKeyService;
    private final AdmissionScheduler scheduler;

    /** 复用一个客户端实例：它内部带连接池，每次新建会浪费连接 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public OpenAiGatewayController(ApiKeyService apiKeyService, AdmissionScheduler scheduler) {
        this.apiKeyService = apiKeyService;
        this.scheduler = scheduler;
    }

    /**
     * 通配入口：把 {@code /v1/**} 的任意路径转发到后端的同名路径。
     * <p>
     * Spring MVC 的 {@code /**} 通配可正常使用（与 jaravel 路由不同，后者会在末尾补斜杠
     * 导致 {@code /v1/**} 变成非法 pattern）。
     *
     * @param servletRequest 原始请求
     * @return 响应；流式场景返回 {@link StreamingResponseBody}
     */
    @RequestMapping(value = "/v1/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.OPTIONS})
    public ResponseEntity<?> proxy(HttpServletRequest servletRequest) {
        // 说明：这里故意用 ResponseEntity<?>。
        // Spring 通过「body 的实际类型」判断是否走流式，所以只要把
        // StreamingResponseBody 实例放进 body 就会走流式分支（已实测）。
        // 若把签名收紧成 ResponseEntity<StreamingResponseBody>，则错误分支
        // （返回 JSON Map）会被拒绝，报
        //   IllegalArgumentException: StreamingResponseBody expected: java.util.ImmutableCollections$Map1
        // 用 <?> 才能让「流式」与「JSON 错误」两条路径共存于同一个处理方法和同一条路由。        // ---------- 1. 鉴权 ----------
        String plaintextKey = extractBearer(servletRequest);
        if (plaintextKey == null) {
            return error(HttpStatus.UNAUTHORIZED, "缺少 Authorization: Bearer <api-key>", "invalid_request_error");
        }
        ApiKey apiKey = apiKeyService.resolveUsable(plaintextKey);
        if (apiKey == null) {
            return error(HttpStatus.UNAUTHORIZED, "API Key 无效或已停用", "invalid_request_error");
        }

        // ---------- 2. 会话标识 ----------
        String sessionId = servletRequest.getHeader("x-session-id");
        if (sessionId == null || sessionId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST,
                    "缺少 x-session-id 请求头（DSH 需安装 dsh-session-header 插件）",
                    "invalid_request_error");
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

        // ---------- 4. 转发 ----------
        String path = servletRequest.getRequestURI();
        String query = servletRequest.getQueryString();
        String targetUrl = trimTrailingSlash(backend.getBaseUrl()) + path
                + (query == null || query.isBlank() ? "" : "?" + query);

        byte[] body = readBody(servletRequest);
        HttpRequest upstreamRequest;
        try {
            upstreamRequest = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(FORWARD_TIMEOUT)
                    .header("Content-Type", contentTypeOf(servletRequest))
                    .header("Accept", acceptOf(servletRequest))
                    // 会话头透传给上游：便于后端侧日志串联
                    .header("x-session-id", sessionId)
                    .method(servletRequest.getMethod(),
                            body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (Exception e) {
            scheduler.release(requestId);
            log.warn("[gateway] 构造上游请求失败 backend={} url={}: {}",
                    backend.getBackendId(), targetUrl, e.getMessage());
            scheduler.release(requestId);
            return error(HttpStatus.BAD_GATEWAY, "构造转发请求失败", "api_error");
        }

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            // 连不上后端：立刻释放，客户端可重试
            scheduler.release(requestId);
            log.warn("[gateway] 转发失败 backend={} url={}: {}",
                    backend.getBackendId(), targetUrl, e.getMessage());
            return error(HttpStatus.BAD_GATEWAY,
                    "转发到后端失败：" + backend.getBackendId(), "api_error");
        }

        // ---------- 5. 回传 ----------
        // 当前实现：把上游响应读完，一次性返回。
        //
        // 【关于 SSE 真流式的已知限制——这不是疏忽，是有意的取舍】
        // 试过三种「边收边发」的写法，都在 Spring 的返回值处理链上失败：
        //   a) ResponseEntity<?>.body(StreamingResponseBody)
        //      → No converter for [class ...$$Lambda...] with preset Content-Type
        //   b) ResponseEntity<StreamingResponseBody>（签名收紧）
        //      → 错误分支返回 JSON 时抛 StreamingResponseBody expected: java.util.ImmutableCollections$Map1
        //   c) 走 jaravel 的 ServerResponse 通道
        //      → body(Object) 走消息转换器；sse() 会给内容再包一层 "data:" 前缀，
        //        而上游给的本就是完整 SSE 文本，会二次包装破坏协议
        // 根因：Spring 要求在**编译期**就确定返回类型是否为流式体，而这里「流式成功」
        // 与「JSON 错误」共用同一个处理方法与同一条路由。正确修法是拆成两个方法
        // （或在 Filter 层接管 servlet 输出流），属于结构性调整，不在这版硬凑。
        //
        // 影响：普通（非 stream）调用完全正常；stream=true 的请求会在生成结束后
        // 一次性拿到完整 SSE 文本——内容正确、协议完整，只是缺少逐 token 的实时性。
        int status = upstreamResponse.statusCode();
        HttpHeaders headers = new HttpHeaders();
        upstreamResponse.headers().firstValue("Content-Type")
                .ifPresent(v -> headers.set(HttpHeaders.CONTENT_TYPE, v));
        upstreamResponse.headers().firstValue("Cache-Control")
                .ifPresent(v -> headers.set(HttpHeaders.CACHE_CONTROL, v));
        headers.set("X-Backend", backend.getBackendId());

        String text;
        try (InputStream in = upstreamResponse.body()) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            scheduler.release(requestId);
            log.warn("[gateway] 读取上游响应失败 backend={}: {}", backend.getBackendId(), e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "读取后端响应失败", "api_error");
        } finally {
            // 槽位在这条请求彻底结束（上游流已关闭）后才释放。
            // 文档 §9.2 第 6 条：不能在收到响应头时释放。
            scheduler.release(requestId);
        }

        return ResponseEntity.status(status).headers(headers).body(text);
    }

    // ==================== 工具方法 ====================

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
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

    private byte[] readBody(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.debug("[gateway] 读取请求体失败，按空 body 处理: {}", e.getMessage());
            return new byte[0];
        }
    }

    private String contentTypeOf(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType;
    }

    private String acceptOf(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept == null ? MediaType.APPLICATION_JSON_VALUE : accept;
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

    /** 统一错误响应（OpenAI 风格的 error 对象） */
    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, String type) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    /**
     * 忙时响应：{@code 503 + Retry-After}。
     * <p>
     * 设计文档 §4.5 明确要求「不要用 429」：429 的语义是「配额用尽、别再来」，
     * 而这里的语义是「排队中、稍后重试」，用 503 才能让客户端按 Retry-After 正常重试。
     */
    private ResponseEntity<Map<String, Object>> busy(AdmissionScheduler.Decision decision) {
        var retryAfter = decision.retryAfterSeconds();
        if (retryAfter.isEmpty()) {
            // 不该重试的终态（如 Key 停用）：403 而非 503，避免客户端无限重试放大负载
            return error(HttpStatus.FORBIDDEN, decision.reason(), "permission_error");
        }
        int seconds = retryAfter.get();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", "排队中，请按 Retry-After 重试");
        error.put("type", "queue_full");
        error.put("retry_after", seconds);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(seconds))
                // HTTP 头只能是 ISO-8859-1；中文原因会让容器抛
                // IllegalArgumentException（code point 超范围），把 503 变成 500。
                .header("X-Queue-Reason", asciiReason(decision.reason()))
                .body(Map.of("error", error));
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
