package com.weacsoft.aiApiCenter.app.model;

import com.weacsoft.jaravel.vendor.database.BaseModel;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import gaarason.database.annotation.Column;
import gaarason.database.annotation.Primary;
import gaarason.database.annotation.Table;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * GPU 后端池，对齐 {@code backend_pools} 表（文档 §5.3）。
 * <p>
 * {@link #capacity} 是该后端在同一时刻允许的 in-flight 请求数（vLLM {@code max_num_seqs}
 * 通常为 1，个别后端可能更高）。{@code last_health_status} 取值
 * {@code UNKNOWN / HEALTHY / UNHEALTHY}。
 */
@Repository
@Table(name = "backend_pools")
public class BackendPool extends BaseModel<BackendPool, Long> {

    public static final String HEALTH_UNKNOWN = "UNKNOWN";
    public static final String HEALTH_HEALTHY = "HEALTHY";
    public static final String HEALTH_UNHEALTHY = "UNHEALTHY";

    @Primary
    @Column(name = "id")
    private Long id;

    /** 业务唯一标识（调度与 Redis 信号量 key 都用它） */
    @Column(name = "backend_id")
    private String backendId;

    @Column(name = "name")
    private String name;

    /** vLLM 的 OpenAI 兼容端点，如 http://192.168.1.10:8000 */
    @Column(name = "base_url")
    private String baseUrl;

    /** 并发容量，默认 1 */
    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "enabled")
    private Boolean enabled;

    /** 健康检查路径，默认 /health */
    @Column(name = "health_path")
    private String healthPath;

    @Column(name = "last_health_status")
    private String lastHealthStatus;

    @Column(name = "last_checked_at")
    private String lastCheckedAt;

    /**
     * 访问该上游所需的 API Key。
     * <p>
     * <b>与客户端 Key 的区别</b>：客户端拿的是中间件颁发的 {@code sk-ac-*}，
     * 中转发时必须换成后端自己的 Key，否则上游会 401。
     * <p>
     * 存明文——转发时要原样取用，无法哈希。界面上只回显掩码。
     */
    @Column(name = "api_key")
    private String apiKey;

    /**
     * 鉴权头名，默认 {@code Authorization}。
     * <p>
     * 保留可配是为了兼容少数用自定义头（如 {@code api-key}）的网关。
     */
    @Column(name = "auth_header")
    private String authHeader;

    /** 该后端的主模型名（健康检查探测 /v1/models 时参考） */
    @Column(name = "default_model")
    private String defaultModel;

    /**
     * 该后端要求的最低 Key 等级，默认 0（不限制）。
     * <p>
     * 例：设为 1 时，只有 {@code level >= 1} 的 Key 才会被调度到本后端。
     * 用于把高价值算力保留给高等级 Key。
     */
    @Column(name = "min_level")
    private Integer minLevel;

    @Column(name = "created_at", fill = TimestampFill.CreatedTimeStringFill.class)
    private String createdAt;

    @Column(name = "updated_at", fill = TimestampFill.UpdatedTimeStringFill.class)
    private String updatedAt;

    public static BackendPool self() {
        return BaseModel.self(BackendPool.class);
    }

    /**
     * 按主键查。
     * <p>
     * 方法名不叫 {@code find}：与 gaarason {@code Model} 的实例方法同名同参会让编译失败。
     */
    public static BackendPool findById(Long id) {
        if (id == null) {
            return null;
        }
        // find() 在无匹配记录时返回 null，不是空 Record
        gaarason.database.contract.eloquent.Record<BackendPool, Long> record =
                BackendPool.self().find(id);
        return record == null ? null : record.toObject();
    }

    public static BackendPool findByBackendId(String backendId) {
        if (backendId == null || backendId.isBlank()) {
            return null;
        }
        // first() 在无匹配记录时返回 null，不是空 Record
        gaarason.database.contract.eloquent.Record<BackendPool, Long> record =
                BackendPool.self().newQuery().where("backend_id", backendId).first();
        return record == null ? null : record.toObject();
    }

    public static List<BackendPool> all() {
        return BackendPool.self().newQuery()
                .orderBy("id", gaarason.database.appointment.OrderBy.ASC)
                .get()
                .toObjectList();
    }

    /** 参与调度的后端（enabled=true） */
    public static List<BackendPool> schedulable() {
        return BackendPool.self().newQuery()
                .where("enabled", true)
                .orderBy("id", gaarason.database.appointment.OrderBy.ASC)
                .get()
                .toObjectList();
    }

    /** 是否参与调度 */
    public boolean isUsable() {
        return Boolean.TRUE.equals(enabled) && capacity != null && capacity > 0;
    }

    /** 健康检查实际路径（空则回退 /health） */
    public String resolvedHealthPath() {
        return (healthPath == null || healthPath.isBlank()) ? "/health" : healthPath;
    }

    // ---------------- getter / setter ----------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBackendId() { return backendId; }
    public void setBackendId(String backendId) { this.backendId = backendId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Integer getMinLevel() { return minLevel == null ? 0 : minLevel; }
    public void setMinLevel(Integer minLevel) { this.minLevel = minLevel; }

    /** 指定等级的 Key 是否允许使用本后端 */
    public boolean allowsLevel(int keyLevel) {
        return keyLevel >= getMinLevel();
    }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getHealthPath() { return healthPath; }
    public void setHealthPath(String healthPath) { this.healthPath = healthPath; }

    /** 上游 Key；空表示该后端不要求鉴权 */
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getAuthHeader() { return authHeader; }
    public void setAuthHeader(String authHeader) { this.authHeader = authHeader; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    /** 实际使用的鉴权头名（空则 Authorization） */
    public String resolvedAuthHeader() {
        return (authHeader == null || authHeader.isBlank()) ? "Authorization" : authHeader.trim();
    }

    /** 是否有上游 Key */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Key 的掩码形式，供界面回显。
     * <p>
     * 只露前 6 后 4，中间固定星号——既能让运维确认「填的是哪个 Key」，
     * 又不会把完整凭证留在页面上（截图、录屏、共享屏幕都可能泄露）。
     *
     * @return 掩码；无 Key 返回空串
     */
    public String maskedApiKey() {
        if (!hasApiKey()) {
            return "";
        }
        String k = apiKey.trim();
        if (k.length() <= 10) {
            return "****";
        }
        return k.substring(0, 6) + "****" + k.substring(k.length() - 4);
    }

    public String getLastHealthStatus() { return lastHealthStatus; }
    public void setLastHealthStatus(String lastHealthStatus) { this.lastHealthStatus = lastHealthStatus; }

    public String getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(String lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
