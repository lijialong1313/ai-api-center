package com.weacsoft.aiApiCenter.app.service;

import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * GPU 后端管理（文档 §5.3）。
 * <p>
 * 后端就是 vLLM 的 OpenAI 兼容端点，{@link BackendPool#getCapacity()} 决定同一时刻
 * 允许的 in-flight 请求数（文档 §1.1：大多数后端容量为 1，个别可能更高）。
 * <p>
 * 健康检查向 {@code baseUrl + health_path}（默认 {@code /health}）发 GET；
 * 404 时回退探测 {@code /v1/models}——部分 vLLM 部署只开放后者。
 * 结果写回 {@code last_health_status / last_checked_at}，供后台列表与调度器使用。
 */
@Service
public class BackendPoolService {

    private static final Logger log = LoggerFactory.getLogger(BackendPoolService.class);

    /** 健康探测超时：短，避免拖住后台请求 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final SchedulerConfigCache configCache;

    public BackendPoolService(SchedulerConfigCache configCache) {
        this.configCache = configCache;
    }

    /**
     * 新建后端。
     *
     * @param backendId 唯一标识（空则自动生成）
     * @param name      可读名称
     * @param baseUrl   vLLM 端点
     * @param capacity  并发容量（&lt;1 纠正为 1）
     * @param healthPath 健康路径（空则 /health）
     * @return 已持久化的实体
     */
    public BackendPool create(String backendId, String name, String baseUrl,
                              Integer capacity, String healthPath) {
        BackendPool pool = new BackendPool();
        pool.setBackendId((backendId == null || backendId.isBlank())
                ? "gpu-" + UUID.randomUUID().toString().substring(0, 8)
                : backendId.trim());
        pool.setName(name);
        pool.setBaseUrl(normalizeBaseUrl(baseUrl));
        pool.setCapacity(capacity == null || capacity < 1 ? 1 : capacity);
        pool.setEnabled(true);
        pool.setHealthPath((healthPath == null || healthPath.isBlank()) ? "/health" : healthPath.trim());
        pool.setLastHealthStatus(BackendPool.HEALTH_UNKNOWN);
        pool.setCreatedAt(TimestampFill.nowString());
        pool.setUpdatedAt(TimestampFill.nowString());
        BackendPool saved = pool.save();
        configCache.invalidateWeights();
        log.info("[backend] 新建后端: backendId={}, baseUrl={}, capacity={}",
                pool.getBackendId(), pool.getBaseUrl(), pool.getCapacity());
        return saved;
    }

    public List<BackendPool> list() {
        return BackendPool.all();
    }

    public BackendPool get(Long id) {
        return BackendPool.findById(id);
    }

    /** 参与调度的后端（enabled 且 capacity&gt;0） */
    public List<BackendPool> schedulable() {
        return BackendPool.schedulable();
    }

    /**
     * 修改后端。只更新传入的非空字段。
     * <p>
     * <b>容量变更</b>（文档 §5.3）：容量对应槽位计数，改动后必须让调度器的槽位状态同步；
     * 这里统一 invalidate 配置缓存，槽位在下一轮调度时按新容量重建。
     */
    public BackendPool update(Long id, String name, String baseUrl, Integer capacity,
                              Boolean enabled, String healthPath) {
        BackendPool pool = BackendPool.findById(id);
        if (pool == null) {
            return null;
        }
        if (name != null) {
            pool.setName(name);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            pool.setBaseUrl(normalizeBaseUrl(baseUrl));
        }
        if (capacity != null) {
            pool.setCapacity(capacity < 1 ? 1 : capacity);
        }
        if (enabled != null) {
            pool.setEnabled(enabled);
        }
        if (healthPath != null && !healthPath.isBlank()) {
            pool.setHealthPath(healthPath.trim());
        }
        pool.setUpdatedAt(TimestampFill.nowString());
        BackendPool saved = pool.save();
        configCache.invalidateWeights();
        log.info("[backend] 更新后端: id={}, capacity={}, enabled={}", id, pool.getCapacity(), pool.getEnabled());
        return saved;
    }

    public boolean delete(Long id) {
        BackendPool pool = BackendPool.findById(id);
        if (pool == null) {
            return false;
        }
        BackendPool.self().newQuery().where("id", id).delete();
        configCache.invalidateWeights();
        log.info("[backend] 删除后端: id={}", id);
        return true;
    }

    /**
     * 主动健康检查（文档 §5.3 的 {@code POST /admin/backends/{id}/health-check}）。
     *
     * @param id 后端主键
     * @return 检查结果；后端不存在返回 null
     */
    public HealthResult healthCheck(Long id) {
        BackendPool pool = BackendPool.findById(id);
        if (pool == null) {
            return null;
        }
        HealthResult result = probe(pool);
        pool.setLastHealthStatus(result.healthy()
                ? BackendPool.HEALTH_HEALTHY : BackendPool.HEALTH_UNHEALTHY);
        pool.setLastCheckedAt(TimestampFill.nowString());
        pool.setUpdatedAt(TimestampFill.nowString());
        pool.save();
        log.info("[backend] 健康检查: backendId={}, status={}, detail={}",
                pool.getBackendId(), pool.getLastHealthStatus(), result.detail());
        return result;
    }

    /**
     * 探测一个后端：先打 {@code health_path}，404/异常时回退 {@code /v1/models}。
     * <p>
     * 任一探测返回 2xx 即视为健康——vLLM 在加载模型期间可能对健康接口返回 503，
     * 那种情况诚实标记为不健康，由调度器跳过它。
     */
    public HealthResult probe(BackendPool pool) {
        String base = normalizeBaseUrl(pool.getBaseUrl());
        if (base == null || base.isBlank()) {
            return new HealthResult(false, "base_url 为空", 0);
        }
        RestClient client = RestClient.builder()
                .baseUrl(base)
                .requestFactory(requestFactory())
                .build();

        HealthResult primary = get(client, pool.resolvedHealthPath());
        if (primary.healthy()) {
            return primary;
        }
        if (primary.status() == 404) {
            HealthResult fallback = get(client, "/v1/models");
            if (fallback.healthy()) {
                return fallback;
            }
            return new HealthResult(false,
                    "健康路径返回 404，回退 /v1/models 仍失败：" + fallback.detail(), fallback.status());
        }
        return primary;
    }

    /** 发一次 GET 并归类结果 */
    private HealthResult get(RestClient client, String path) {
        try {
            var response = client.get().uri(path).retrieve().toBodilessEntity();
            int status = response.getStatusCode().value();
            boolean ok = status >= 200 && status < 300;
            return new HealthResult(ok, path + " → HTTP " + status, status);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            return new HealthResult(false, path + " → HTTP " + status, status);
        } catch (Exception e) {
            return new HealthResult(false, path + " → " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), 0);
        }
    }

    /** 带超时的请求工厂（避免后端不可达时后台请求被拖住） */
    private org.springframework.http.client.ClientHttpRequestFactory requestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) PROBE_TIMEOUT.toMillis());
        factory.setReadTimeout((int) PROBE_TIMEOUT.toMillis());
        return factory;
    }

    /** 去掉末尾斜杠，避免拼出 {@code //health} */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 健康检查结果。
     *
     * @param healthy 是否健康
     * @param detail  探测细节（路径 + 状态码 / 异常）
     * @param status  HTTP 状态码，0 表示未拿到响应
     */
    public record HealthResult(boolean healthy, String detail, int status) {
    }
}
