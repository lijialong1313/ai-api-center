package com.weacsoft.aiApiCenter.app.service;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * API Key 管理（文档 §5.2）。
 * <p>
 * 鉴权按 {@code key_hash}（SHA-256）查询；{@code key_value} 存明文，
 * 供后台随时查看与复制。界面默认打码，日志不输出该字段。
 * <p>
 * 权重或启停状态变更后调用 {@link SchedulerConfigCache#invalidateWeights()}，
 * 使调度器立即读到新配置。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** Key 明文前缀，便于在日志/配置里识别这是本系统颁发的 Key */
    private static final String KEY_PREFIX = "sk-ac-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SchedulerConfigCache configCache;

    public ApiKeyService(SchedulerConfigCache configCache) {
        this.configCache = configCache;
    }

    /**
     * 新建一个 Key。
     *
     * @param label  可读名称
     * @param weight 相对权重（&lt;=0 会被纠正为 1）
     * @return 新建结果，{@code plaintext} 为明文
     */
    public CreatedKey create(String label, int weight) {
        return create(label, weight, 0);
    }

    /**
     * 新建 Key（带等级）。
     *
     * @param label  名称
     * @param weight 权重
     * @param level  等级，数值越大等级越高；用于分级调度
     * @return 新建结果
     */
    public CreatedKey create(String label, int weight, int level) {
        String plaintext = generatePlaintext();
        ApiKey key = new ApiKey();
        key.setKeyHash(sha256(plaintext));
        // 同时存明文：后台需要随时复制 Key，只存哈希就无法回显
        key.setKeyValue(plaintext);
        key.setKeyPrefix(plaintext.substring(0, Math.min(11, plaintext.length())));
        key.setLabel(label);
        key.setWeight(weight <= 0 ? 1 : weight);
        key.setLevel(Math.max(0, level));
        key.setEnabled(true);
        key.setCreatedAt(TimestampFill.nowString());
        key.setUpdatedAt(TimestampFill.nowString());
        ApiKey saved = key.save();
        log.info("[api-key] 新建 Key: id={}, label={}, weight={}",
                saved != null ? saved.getId() : null, label, key.getWeight());
        return new CreatedKey(saved, plaintext);
    }

    /** 全部 Key（不含明文） */
    public List<ApiKey> list() {
        return ApiKey.all();
    }

    /** 按主键取 */
    public ApiKey get(Long id) {
        return ApiKey.findById(id);
    }

    /**
     * 修改名称 / 权重 / 启用状态。
     * <p>
     * 每个参数传 {@code null} 表示「不修改」。这样界面上只改名称时不会误把权重清零。
     *
     * @param id      主键
     * @param label   名称，null=不改
     * @param weight  权重，null=不改；&lt;=0 会被纠正为 1
     * @param enabled 启用状态，null=不改
     * @return 更新后的实体，不存在返回 null
     */
    public ApiKey update(Long id, String label, Integer weight, Boolean enabled) {
        return update(id, label, weight, enabled, null);
    }

    /**
     * 修改名称 / 权重 / 启用状态 / 等级。
     *
     * @param level 等级，null=不改
     * @return 更新后的实体
     */
    public ApiKey update(Long id, String label, Integer weight, Boolean enabled, Integer level) {
        ApiKey key = ApiKey.findById(id);
        if (key == null) {
            return null;
        }
        if (label != null) {
            // 允许把名称清成空串（用户主动清空），此时存 null 更干净
            key.setLabel(label.isBlank() ? null : label.trim());
        }
        if (weight != null) {
            key.setWeight(weight <= 0 ? 1 : weight);
        }
        if (enabled != null) {
            key.setEnabled(enabled);
        }
        if (level != null) {
            key.setLevel(Math.max(0, level));
        }
        key.setUpdatedAt(TimestampFill.nowString());
        ApiKey saved = key.save();
        // 权重 / 启用状态变了，调度器缓存的配置必须立刻失效
        configCache.invalidateWeights();
        log.info("[api-key] 更新 Key: id={}, label={}, weight={}, enabled={}",
                id, key.getLabel(), key.getWeight(), key.getEnabled());
        return saved;
    }

    /**
     * 重新生成 Key 明文（保留名称、权重、启用状态与主键）。
     * <p>
     * Key 泄露或丢失时原地换值：元数据保留，客户端只需更新一个字符串。
     * 换值后旧 Key 立即失效——鉴权按 {@code key_hash} 查询，哈希已改。
     *
     * @param id 主键
     * @return 新明文；Key 不存在返回 null
     */
    public String regenerate(Long id) {
        ApiKey key = ApiKey.findById(id);
        if (key == null) {
            return null;
        }
        String plaintext = generatePlaintext();
        key.setKeyHash(sha256(plaintext));
        key.setKeyValue(plaintext);
        key.setKeyPrefix(plaintext.substring(0, Math.min(11, plaintext.length())));
        key.setUpdatedAt(TimestampFill.nowString());
        key.save();
        configCache.invalidateWeights();
        log.info("[api-key] 重新生成 Key: id={}（旧值已立即失效）", id);
        return plaintext;
    }

    /** 启用 / 禁用 */
    public ApiKey setEnabled(Long id, boolean enabled) {
        return update(id, null, null, enabled);
    }

    /** 删除 Key（物理删除：Key 本身没有审计价值，删除即失效） */
    public boolean delete(Long id) {
        ApiKey key = ApiKey.findById(id);
        if (key == null) {
            return false;
        }
        ApiKey.self().newQuery().where("id", id).delete();
        configCache.invalidateWeights();
        log.info("[api-key] 删除 Key: id={}", id);
        return true;
    }

    /**
     * 按明文解析出可用 Key（准入鉴权用）。
     *
     * @param plaintext 请求 {@code Authorization} 中的 Key
     * @return 可用的 Key；不存在或被禁用返回 null
     */
    public ApiKey resolveUsable(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        ApiKey key = ApiKey.findByHash(sha256(plaintext.trim()));
        if (key == null || !key.isUsable()) {
            return null;
        }
        return key;
    }

    /** 记录最近使用时间（尽力而为，失败不影响准入） */
    public void touch(ApiKey key) {
        try {
            key.setLastUsedAt(TimestampFill.nowString());
            key.save();
        } catch (Exception e) {
            log.debug("[api-key] 更新 last_used_at 失败（忽略）: {}", e.getMessage());
        }
    }

    /** 生成 Key 明文：前缀 + 32 字节 URL-safe Base64 */
    private String generatePlaintext() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** SHA-256 十六进制 */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 新建结果。
     *
     * @param key       已持久化的实体
     * @param plaintext Key 明文
     */
    public record CreatedKey(ApiKey key, String plaintext) {
    }
}
