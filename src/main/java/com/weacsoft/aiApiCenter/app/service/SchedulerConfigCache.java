package com.weacsoft.aiApiCenter.app.service;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.jaravel.vendor.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度器配置缓存（文档 §5.2「权重即时生效」、§8.1「配置同步」）。
 * <p>
 * 两个层次：
 * <ol>
 *   <li><b>进程内缓存</b>：{@link #weights()} 把 {@code api_keys} 的权重缓存起来，
 *       调度循环每轮直接读内存，不查库；后台改权重时 {@link #invalidateWeights()} 立刻清掉。</li>
 *   <li><b>跨实例同步</b>：把权重同时写入 <b>jaravel {@code Cache}</b>（{@code key_weight:<hash>}）。
 *       无 Redis 时是单机内存/数据库缓存，有 Redis 时 {@code redis-cache} 自动跨机同步——
 *       业务代码对四种部署模式完全一致（文档 §10.3「唯一需要感知部署模式的地方是配置」）。</li>
 * </ol>
 * <p>
 * 权重缓存只存「可用 Key 的权重」，禁用或权重为 0 的 Key 直接不在 Map 里，
 * 调度器遍历时天然跳过它们，不需要额外判断。
 */
@Service
public class SchedulerConfigCache {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfigCache.class);

    /** 权重缓存的 Cache key 前缀（对齐文档 §5.2 的 {@code key_weight:}） */
    public static final String WEIGHT_CACHE_PREFIX = "key_weight:";

    /** 进程内权重表：apiKey 主键 → 权重（只含可用 Key） */
    private final Map<Long, WeightEntry> localWeights = new ConcurrentHashMap<>();

    /** 本地缓存的加载时间戳，用于 TTL 兜底刷新（多机无 Redis 时靠它感知别人的修改） */
    private volatile long loadedAt = 0L;

    /** 本地权重表的 TTL：即使没人显式失效，也会周期性回源（数据库模式的多机同步兜底） */
    private static final long LOCAL_TTL_MS = 10_000L;

    /**
     * 当前可参与调度的权重表（apiKey 哈希 → 权重）。
     * <p>
     * 调度循环每轮调用一次。命中本地缓存直接返回；过期或为空时回源数据库，
     * 并顺带把权重写入 jaravel Cache（供其它实例/后续读取）。
     *
     * @return 只读视图：Key 的哈希 → 权重
     */
    public Map<String, Integer> weights() {
        if (needsReload()) {
            synchronized (this) {
                if (needsReload()) {
                    reload();
                }
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        localWeights.forEach((id, entry) -> result.put(entry.keyHash(), entry.weight()));
        return result;
    }

    /** 是否为「可用 Key」的权重表为空（用于快速判断有无可调度对象） */
    public boolean isEmpty() {
        return weights().isEmpty();
    }

    /**
     * 权重失效：后台改过 {@code api_keys} 后调用。
     * <p>
     * 同时清掉 jaravel Cache 里对应的键，避免别的实例读到旧值。
     */
    public void invalidateWeights() {
        loadedAt = 0L;
        localWeights.clear();
        try {
            Cache.flush();
        } catch (Exception e) {
            // 缓存未装配或刷新失败都不影响正确性：本地缓存已清空，下次读取会回源
            log.debug("[scheduler] 清理 Cache 失败（忽略）: {}", e.getMessage());
        }
    }

    /** 是否需要回源 */
    private boolean needsReload() {
        return localWeights.isEmpty() || (System.currentTimeMillis() - loadedAt) > LOCAL_TTL_MS;
    }

    /** 回源数据库，刷新本地权重表并写入 Cache */
    private void reload() {
        List<ApiKey> keys = ApiKey.schedulable();
        localWeights.clear();
        for (ApiKey key : keys) {
            int weight = key.getWeight() == null ? 0 : key.getWeight();
            if (weight <= 0) {
                continue;
            }
            localWeights.put(key.getId(), new WeightEntry(key.getKeyHash(), weight));
            putCacheWeight(key.getKeyHash(), weight);
        }
        loadedAt = System.currentTimeMillis();
        log.debug("[scheduler] 权重表已刷新: {} 个可用 Key", localWeights.size());
    }

    /** 把单个权重写入 jaravel Cache（失败不影响调度） */
    private void putCacheWeight(String keyHash, int weight) {
        try {
            Cache.put(WEIGHT_CACHE_PREFIX + keyHash, weight);
        } catch (Exception e) {
            log.debug("[scheduler] 写入权重缓存失败（忽略）: {}", e.getMessage());
        }
    }

    /** 权重条目：Key 哈希 + 权重 */
    private record WeightEntry(String keyHash, int weight) {
    }
}
