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

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getHealthPath() { return healthPath; }
    public void setHealthPath(String healthPath) { this.healthPath = healthPath; }

    public String getLastHealthStatus() { return lastHealthStatus; }
    public void setLastHealthStatus(String lastHealthStatus) { this.lastHealthStatus = lastHealthStatus; }

    public String getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(String lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
