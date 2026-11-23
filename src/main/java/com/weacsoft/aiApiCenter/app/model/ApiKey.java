package com.weacsoft.aiApiCenter.app.model;

import com.weacsoft.jaravel.vendor.database.BaseModel;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import gaarason.database.annotation.Column;
import gaarason.database.annotation.Primary;
import gaarason.database.annotation.Table;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * API Key，对齐 {@code api_keys} 表（文档 §5.2）。
 * <p>
 * 鉴权按 {@link #keyHash}（SHA-256）查询；{@code keyValue} 存明文，供后台复制。
 * 调度按 {@link #weight} 分配服务额度，按 {@link #level} 做分级过滤。
 * <p>
 * {@code weight} 为 0 或 {@code enabled=false} 的 Key，中间件直接拒绝。
 */
@Repository
@Table(name = "api_keys")
public class ApiKey extends BaseModel<ApiKey, Long> {

    @Primary
    @Column(name = "id")
    private Long id;

    /** Key 明文的 SHA-256（十六进制），唯一索引 */
    @Column(name = "key_hash")
    private String keyHash;

    /** 明文前 8 位，便于后台辨认（不足以还原明文） */
    @Column(name = "key_prefix")
    private String keyPrefix;

    @Column(name = "label")
    private String label;

    /** 相对权重，默认 1，用于按权重分配服务额度 */
    @Column(name = "weight")
    private Integer weight;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "last_used_at")
    private String lastUsedAt;

    /**
     * Key 明文，供管理后台复制。
     * <p>
     * 明文存储意味着数据库泄露即可直接使用全部 Key。界面默认显示掩码，日志不输出该字段。
     * 鉴权通过 {@link #keyHash} 查询，本字段仅用于展示与复制。
     */
    @Column(name = "key_value")
    private String keyValue;

    /**
     * 等级，默认 0，数值越大等级越高。
     * <p>
     * 用于分级调度：某些 GPU 可配置「最低等级」门槛，只有达到该等级的 Key 才会被放行到它。
     */
    @Column(name = "level")
    private Integer level;

    @Column(name = "created_at", fill = TimestampFill.CreatedTimeStringFill.class)
    private String createdAt;

    @Column(name = "updated_at", fill = TimestampFill.UpdatedTimeStringFill.class)
    private String updatedAt;

    public static ApiKey self() {
        return BaseModel.self(ApiKey.class);
    }

    /**
     * 按主键查。
     * <p>
     * 方法名不用 {@code find}：与 gaarason {@code Model} 的实例方法同名同参会让编译失败。
     */
    public static ApiKey findById(Long id) {
        if (id == null) {
            return null;
        }
        // find() 在无匹配记录时同样返回 null
        gaarason.database.contract.eloquent.Record<ApiKey, Long> record = ApiKey.self().find(id);
        return record == null ? null : record.toObject();
    }

    /** 按 Key 哈希查（准入鉴权用） */
    public static ApiKey findByHash(String keyHash) {
        if (keyHash == null || keyHash.isBlank()) {
            return null;
        }
        // gaarason 的 first() 在无匹配记录时返回 null，不是空 Record
        gaarason.database.contract.eloquent.Record<ApiKey, Long> record =
                ApiKey.self().newQuery().where("key_hash", keyHash).first();
        return record == null ? null : record.toObject();
    }

    /** 全部 Key（管理后台列表用） */
    public static List<ApiKey> all() {
        return ApiKey.self().newQuery()
                .orderBy("id", gaarason.database.appointment.OrderBy.DESC)
                .get()
                .toObjectList();
    }

    /** 可参与调度的 Key（enabled=true 且 weight&gt;0） */
    public static List<ApiKey> schedulable() {
        return ApiKey.self().newQuery()
                .where("enabled", true)
                .where("weight", ">", 0)
                .get()
                .toObjectList();
    }

    /** 是否可用于调度 */
    public boolean isUsable() {
        return Boolean.TRUE.equals(enabled) && weight != null && weight > 0;
    }

    // ---------------- getter / setter ----------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    /** 明文 Key（可能为空：旧数据没有该列值） */
    public String getKeyValue() { return keyValue; }
    public void setKeyValue(String keyValue) { this.keyValue = keyValue; }

    /**
     * 页面展示用的打码形式。
     * <p>
     * 只露前 11 位（即 {@code sk-ac-xxxx} 前缀段）与后 4 位。
     *
     * @return 打码后的 Key；无明文时回退到前缀
     */
    public String maskedKeyValue() {
        if (keyValue == null || keyValue.isBlank()) {
            return (keyPrefix == null ? "" : keyPrefix) + "…";
        }
        String k = keyValue.trim();
        if (k.length() <= 15) {
            return k.substring(0, Math.min(11, k.length())) + "…";
        }
        return k.substring(0, 11) + "****" + k.substring(k.length() - 4);
    }

    /** 是否可复制（有明文） */
    public boolean hasKeyValue() {
        return keyValue != null && !keyValue.isBlank();
    }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Integer getLevel() { return level == null ? 0 : level; }
    public void setLevel(Integer level) { this.level = level; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(String lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
