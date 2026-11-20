package com.weacsoft.aiApiCenter.app.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 准入状态：会话 in-flight 标记 + 后端槽位占用。
 * <p>
 * 对应设计文档 §4.1 的「会话 in-flight 判定」与「后端槽位判定」。
 * <p>
 * <b>单实例内是权威状态</b>；多实例部署时，本类只保证「本实例看到的」不超限，
 * 跨实例的强一致依赖 fork 点（{@link AdmissionScheduler}）拿到分布式锁之后再判定
 * ——锁由 {@code LockProvider} 提供（Redis 模式为 {@code RedisLockProviderImpl}）。
 * 无 Redis 的多机模式用 database 队列的 {@code pop} 原子性做互斥。
 * <p>
 * <b>两个不变量</b>（文档 §9.1）：
 * <ol>
 *   <li>同一 {@code sessionId} 任意时刻最多一个请求处于「转发到 vLLM」状态；</li>
 *   <li>每个后端在途请求数不超过其 {@code capacity}。</li>
 * </ol>
 * 本类用 CAS 式的原子方法同时维护这两条，调用方只需 {@link #tryAcquire} /
 * {@link #release}，不必自己配锁。
 */
@Component
public class AdmissionState {

    private static final Logger log = LoggerFactory.getLogger(AdmissionState.class);

    /** sessionId → 占用它的请求 id（用于诊断与幂等释放） */
    private final Map<String, String> inflightSessions = new ConcurrentHashMap<>();

    /** backendId → 当前在途数 */
    private final Map<String, Integer> backendInflight = new ConcurrentHashMap<>();

    /** backendId → 容量（由后台配置同步进来，容量变更时调用 {@link #setCapacity}） */
    private final Map<String, Integer> backendCapacity = new ConcurrentHashMap<>();

    /** requestId → 占用信息（幂等释放用：同一请求只能释放一次） */
    private final Map<String, Occupancy> occupancies = new ConcurrentHashMap<>();

    /**
     * 尝试为一次请求同时取得「会话独占」与「后端槽位」。
     * <p>
     * 两者要么都成功，要么都失败（失败会回滚已取得的那一半），保证不会出现
     * 「占了会话但没占槽位」这种泄漏。
     *
     * @param requestId   请求唯一 id（释放时必须用同一个值）
     * @param sessionId   会话 id（同一会话严格串行）
     * @param backendId   目标后端
     * @return 成功返回 true；会话正被占用或后端槽位已满返回 false
     */
    public boolean tryAcquire(String requestId, String sessionId, String backendId) {
        if (requestId == null || backendId == null) {
            return false;
        }
        // 幂等：同一请求重复 acquire 直接视为成功（重试路径可能重复调用）
        if (occupancies.containsKey(requestId)) {
            return true;
        }

        // 1) 会话独占：putIfAbsent 是原子的，天然防并发交错
        if (sessionId != null && !sessionId.isBlank()) {
            String previous = inflightSessions.putIfAbsent(sessionId, requestId);
            if (previous != null && !previous.equals(requestId)) {
                return false;
            }
        }

        // 2) 后端槽位：用 compute 做原子的「读-判-写」，避免 check-then-act 竞态
        int capacity = capacityOf(backendId);
        if (capacity <= 0) {
            // 后端容量为 0 或未配置：不参与调度，回滚会话占用
            releaseSession(sessionId, requestId);
            return false;
        }
        final boolean[] acquired = {false};
        backendInflight.compute(backendId, (key, current) -> {
            int used = current == null ? 0 : current;
            if (used < capacity) {
                acquired[0] = true;
                return used + 1;
            }
            return used;
        });
        if (!acquired[0]) {
            releaseSession(sessionId, requestId);
            return false;
        }

        occupancies.put(requestId, new Occupancy(sessionId, backendId));
        return true;
    }

    /**
     * 释放一次请求占用的会话与槽位。幂等：重复调用不会把计数减成负数。
     *
     * @param requestId 请求 id
     */
    public void release(String requestId) {
        if (requestId == null) {
            return;
        }
        Occupancy occupancy = occupancies.remove(requestId);
        if (occupancy == null) {
            return;
        }
        releaseSession(occupancy.sessionId(), requestId);
        backendInflight.computeIfPresent(occupancy.backendId(), (key, current) -> {
            int next = current - 1;
            return next <= 0 ? 0 : next;
        });
    }

    /** 只释放会话标记（acquire 失败回滚时用） */
    private void releaseSession(String sessionId, String requestId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        inflightSessions.remove(sessionId, requestId);
    }

    /**
     * 设置后端容量。容量变化时（文档 §5.3）同步槽位视图。
     *
     * @param backendId 后端标识
     * @param capacity  新容量
     */
    public void setCapacity(String backendId, int capacity) {
        if (backendId == null) {
            return;
        }
        backendCapacity.put(backendId, Math.max(0, capacity));
        log.debug("[admission] 后端容量更新: {}={}", backendId, capacity);
    }

    /** 移除后端（后台删除该后端时调用，避免容量表残留） */
    public void removeBackend(String backendId) {
        backendCapacity.remove(backendId);
        backendInflight.remove(backendId);
    }

    /** 后端容量（未配置视为 0，即不参与调度） */
    public int capacityOf(String backendId) {
        Integer capacity = backendCapacity.get(backendId);
        return capacity == null ? 0 : capacity;
    }

    /** 后端当前在途数 */
    public int inflightOf(String backendId) {
        Integer used = backendInflight.get(backendId);
        return used == null ? 0 : used;
    }

    /** 该后端是否还有空槽（capacity - inflight &gt; 0） */
    public boolean hasFreeSlot(String backendId) {
        return inflightOf(backendId) < capacityOf(backendId);
    }

    /** 会话是否已被占用 */
    public boolean isSessionBusy(String sessionId) {
        return sessionId != null && inflightSessions.containsKey(sessionId);
    }

    /** 当前所有在途会话（诊断用） */
    public Set<String> busySessions() {
        return Set.copyOf(inflightSessions.keySet());
    }

    /** 总在途数 */
    public int totalInflight() {
        return backendInflight.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 清空（测试用） */
    public void reset() {
        inflightSessions.clear();
        backendInflight.clear();
        backendCapacity.clear();
        occupancies.clear();
    }

    /** 一次请求的占用信息 */
    private record Occupancy(String sessionId, String backendId) {
    }
}
