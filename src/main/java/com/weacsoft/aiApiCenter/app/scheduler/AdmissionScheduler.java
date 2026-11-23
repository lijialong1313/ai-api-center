package com.weacsoft.aiApiCenter.app.scheduler;

import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.aiApiCenter.app.service.BackendPoolService;
import com.weacsoft.aiApiCenter.app.service.SchedulerConfigCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 准入调度器：决定「这一次请求现在能不能进 vLLM，进哪个后端」。
 * <p>
 * 本类只做决策，不负责转发。调用方取得 {@link Decision#admitted()} 后自行转发，
 * 结束时必须调用 {@link #release(String)} 释放会话与槽位（文档 §9.2 第 6 条）。
 * <p>
 * 调度模型为请求驱动：客户端按 {@code 503 + Retry-After} 短轮询，每次重试都重新
 * 走一遍准入判断，服务端不维护常驻调度线程与队列排序。由此：
 * <ul>
 *   <li>不存在「调度器取出请求但客户端已放弃」的僵尸状态；</li>
 *   <li>排队位置由客户端重试行为体现；</li>
 *   <li>决策本身是单次原子操作，四种部署模式行为一致。</li>
 * </ul>
 * 排队不保证严格 FIFO；公平性按 Key 权重分配（见 {@link WeightedFairnessGate}）。
 */
@Component
public class AdmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdmissionScheduler.class);

    /** Retry-After 下限（秒）：太小的值会让客户端高频空转 */
    private static final int MIN_RETRY_AFTER_SECONDS = 1;

    /** Retry-After 上限（秒）：文档 §4.5 要求封顶 120 秒 */
    private static final int MAX_RETRY_AFTER_SECONDS = 120;

    private final AdmissionState state;
    private final SchedulerConfigCache configCache;
    private final BackendPoolService backendPoolService;

    /** 按权重分配服务额度的公平闸门（单线程访问，见本类的串行说明） */
    private final WeightedFairnessGate fairness;

    /** 决策临界区锁：保证「检查 → 放行」不被并发交错（文档 §9.2 第 3 条） */
    private final Object decisionLock = new Object();

    /** 最近一次同步后端容量的时间戳 */
    private volatile long capacitiesSyncedAt = 0L;

    /** 容量同步间隔：后台可能改容量，定期回源 */
    private static final long CAPACITY_SYNC_INTERVAL_MS = 5_000L;

    public AdmissionScheduler(AdmissionState state,
                              SchedulerConfigCache configCache,
                              BackendPoolService backendPoolService,
                              WeightedFairnessGate fairness) {
        this.state = state;
        this.configCache = configCache;
        this.backendPoolService = backendPoolService;
        this.fairness = fairness;
    }

    /**
     * 准入决策。
     * <p>
     * 判定顺序（任一不满足即拒绝并给出 Retry-After）：
     * <ol>
     *   <li>Key 必须存在且启用（由调用方做鉴权，这里信任传入的 keyHash）；</li>
     *   <li>该会话不能已有在途请求 —— 保证同会话严格串行（不变量 1）；</li>
     *   <li>必须存在有空槽的后端 —— 保证单后端不超容量（不变量 2）；</li>
     *   <li>按平滑加权轮询选出本轮的 Key；本轮不是它则要求稍后重试。</li>
     * </ol>
     *
     * @param requestId 本次请求唯一 id（转发结束用于释放）
     * @param sessionId 会话 id（必填，文档 §9.2 第 7 条：未携带应拒绝）
     * @param keyHash   API Key 的 SHA-256（用于取权重）
     * @return 决策结果
     */
    public Decision decide(String requestId, String sessionId, String keyHash) {
        return decide(requestId, sessionId, keyHash, 0);
    }

    /**
     * 准入决策（带 Key 等级）。
     * <p>
     * 只考虑 {@code minLevel <= keyLevel} 的后端。所有有空槽的后端均因等级门槛不可用时，
     * 返回「等级不足」并让客户端重试，不放行到不该给它使用的后端。
     *
     * @param requestId 请求唯一 id
     * @param sessionId 会话 id
     * @param keyHash   Key 哈希
     * @param keyLevel  Key 等级（数值越大等级越高）
     * @return 决策结果
     */
    public Decision decide(String requestId, String sessionId, String keyHash, int keyLevel) {
        if (sessionId == null || sessionId.isBlank()) {
            return Decision.reject(0, "缺少 x-session-id");
        }
        if (keyHash == null || keyHash.isBlank()) {
            return Decision.reject(0, "缺少 API Key");
        }

        synchronized (decisionLock) {
            syncCapacitiesIfNeeded();

            // 0) 终态判断：Key 已停用或权重为 0 时不再服务，且不返回 Retry-After。
            //    必须排在「会话忙 / 槽位满」这些可重试判断之前，否则已停用的 Key
            //    会收到 503 并按指示持续重试，始终无法成功。
            Map<String, Integer> weights = configCache.weights();
            Integer thisWeight = weights.get(keyHash);
            if (thisWeight == null || thisWeight <= 0) {
                return Decision.reject(0, "该 API Key 已停用或权重为 0");
            }

            // 1) 会话已占用 → 必须等（同会话严格串行，不与其他会话交错）
            if (state.isSessionBusy(sessionId)) {
                return Decision.reject(retryAfterSeconds(), "同一会话已有在途请求");
            }

            // 2) 后端槽位
            List<BackendPool> candidates = backendPoolService.schedulable();
            List<BackendPool> available = new ArrayList<>();
            int levelBlocked = 0;
            for (BackendPool backend : candidates) {
                if (!state.hasFreeSlot(backend.getBackendId())) {
                    continue;
                }
                // 等级门槛：该后端只服务达到 minLevel 的 Key。
                // 单独计数，用于在「全被等级挡住」时返回准确原因。
                if (!backend.allowsLevel(keyLevel)) {
                    levelBlocked++;
                    continue;
                }
                available.add(backend);
            }
            if (available.isEmpty()) {
                if (levelBlocked > 0) {
                    return Decision.reject(retryAfterSeconds(),
                            "该 Key 等级不足以使用当前空闲后端");
                }
                return Decision.reject(retryAfterSeconds(), "所有 GPU 后端已满");
            }

            // 3) 加权公平：配额制。权重视为服务额度，比较窗口内已服务次数与应得份额
            //    （weight / totalWeight）；份额未用完则放行，用完后让路。
            if (weights.isEmpty()) {
                return Decision.reject(retryAfterSeconds(), "没有可用的 API Key");
            }
            if (!fairness.allow(keyHash, weights)) {
                return Decision.reject(shortRetryAfter(thisWeight, weights), "本轮轮到其他 Key");
            }

            // 4) 选后端：优先选在途数最少的（天然负载均衡），容量均为 1 时等价于轮转
            BackendPool chosen = available.get(0);
            for (BackendPool backend : available) {
                if (state.inflightOf(backend.getBackendId()) < state.inflightOf(chosen.getBackendId())) {
                    chosen = backend;
                }
            }

            // 5) 落闸：同时占会话与槽位
            if (!state.tryAcquire(requestId, sessionId, chosen.getBackendId())) {
                // 并发下刚检查完就被其他线程抢占。必须回滚上一步 fairness.allow() 的记账，
                // 否则该 Key 消耗了额度却没有获得服务，长期比例低于其权重。
                fairness.rollback(keyHash);
                return Decision.reject(retryAfterSeconds(), "槽位竞争失败，稍后重试");
            }

            return Decision.admit(chosen, requestId);
        }
    }

    /**
     * 释放某次请求占用的会话与槽位。
     * <p>
     * 必须在流式响应彻底结束后调用（{@code doFinally}），不能在收到响应头时调用
     * （文档 §9.2 第 6 条）。
     *
     * @param requestId 请求 id
     */
    public void release(String requestId) {
        state.release(requestId);
    }

    /**
     * 定期把后台配置的后端容量同步进 {@link AdmissionState}。
     * <p>
     * 容量表为空时跳过节流立即回源：{@code AdmissionState} 可能被 {@code reset()} 清空
     * （测试或运维整体重建后端），若仍按时间戳节流，本轮决策读到的容量全为 0，
     * 会把所有请求判成「后端已满」。
     */
    private void syncCapacitiesIfNeeded() {
        boolean stateEmpty = backendPoolService.schedulable().stream()
                .anyMatch(b -> state.capacityOf(b.getBackendId()) <= 0);
        long now = System.currentTimeMillis();
        if (!stateEmpty && (now - capacitiesSyncedAt < CAPACITY_SYNC_INTERVAL_MS)) {
            return;
        }
        capacitiesSyncedAt = now;
        for (BackendPool backend : backendPoolService.schedulable()) {
            int capacity = backend.getCapacity() == null ? 0 : backend.getCapacity();
            if (Boolean.TRUE.equals(backend.getEnabled())) {
                state.setCapacity(backend.getBackendId(), capacity);
            }
        }
    }

    /**
     * Retry-After 启发式估算（文档 §4.5）。
     * <p>
     * 按在途请求数给一个保守切片，并夹在上下限之间。
     *
     * @return 建议重试秒数
     */
    private int retryAfterSeconds() {
        int inflight = state.totalInflight();
        return Math.max(MIN_RETRY_AFTER_SECONDS, Math.min(MAX_RETRY_AFTER_SECONDS,
                inflight <= 0 ? 2 : 2 + inflight));
    }

    /**
     * 本轮轮到别的 Key 时给一个更短的重试间隔。
     * <p>
     * 权重越高，被选中的频率越高，等待应越短：
     * {@code retryAfter = base * totalWeight / weight}，夹在
     * [{@link #MIN_RETRY_AFTER_SECONDS}, {@link #MAX_RETRY_AFTER_SECONDS}] 之间。
     *
     * @param weight 本 Key 权重
     * @param all    全部 Key 权重
     * @return 建议重试秒数
     */
    private int shortRetryAfter(int weight, Map<String, Integer> all) {
        int total = all.values().stream().mapToInt(Integer::intValue).sum();
        if (weight <= 0 || total <= 0) {
            return MIN_RETRY_AFTER_SECONDS;
        }
        int seconds = (int) Math.ceil(2.0 * total / weight);
        return Math.max(MIN_RETRY_AFTER_SECONDS, Math.min(MAX_RETRY_AFTER_SECONDS, seconds));
    }

    /** 当前在途总数（诊断用） */
    public int inflightCount() {
        return state.totalInflight();
    }

    /** 当前忙碌会话数（诊断用） */
    public int busySessionCount() {
        return state.busySessions().size();
    }

    /**
     * 准入决策结果。
     *
     * @param admitted     是否放行
     * @param backend      放行时选中的后端（未放行为 null）
     * @param requestId    请求 id（放行时必须原样用于 release）
     * @param retryAfter   拒绝时建议的重试秒数；0 表示「不要重试」（如 Key 已停用）
     * @param reason       拒绝原因（诊断 / 日志用，不回传给客户端细节）
     */
    public record Decision(boolean admitted, BackendPool backend, String requestId,
                           int retryAfter, String reason) {

        static Decision admit(BackendPool backend, String requestId) {
            return new Decision(true, backend, requestId, 0, "admitted");
        }

        static Decision reject(int retryAfter, String reason) {
            return new Decision(false, null, null, retryAfter, reason);
        }

        /** 是否应该让客户端重试（retryAfter &gt; 0） */
        public Optional<Integer> retryAfterSeconds() {
            return retryAfter > 0 ? Optional.of(retryAfter) : Optional.empty();
        }
    }
}
