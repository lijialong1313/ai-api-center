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
 * 对应设计文档 §4.2 的准入流程。本类只做<b>决策</b>，不负责转发；调用方拿到
 * {@link Decision#admitted()} 之后自己去转发，结束时必须调
 * {@link #release(String)} 释放占用的会话与槽位（文档 §9.2 第 6 条：
 * 槽位释放必须在流式 {@code doFinally} 中，不能在收到响应头时释放）。
 * <p>
 * <b>为什么不做成「独立调度循环 + 队列 pop」</b>：
 * 文档 §4 描述的是「客户端短轮询 + 503 Retry-After」模型——客户端每次重试都重新
 * 走一遍准入判断，因此调度决策是<b>请求驱动</b>的，不需要服务端维护一个常驻
 * 调度线程去从队列里捞人。这样做的直接好处是：
 * <ul>
 *   <li>没有「调度器取出了请求但客户端已经放弃」的僵尸状态，租约问题天然消失；</li>
 *   <li>排队位置由客户端重试次数体现，无需服务端队列排序；</li>
 *   <li>四种部署模式下行为完全一致，因为决策本身是单次原子操作。</li>
 * </ul>
 * 代价是排队不是严格 FIFO——但设计文档 §4.3 要的本来就是<b>按 Key 加权公平</b>，
 * 而非全局 FIFO，加权轮询的状态在这里已经体现。
 * <p>
 * 若将来需要「服务端主动拉取式」调度（例如支持长时间挂起的单个长连接），
 * 可以在本类之上再加一个基于 {@code QueueDriver} 的循环，本类的决策逻辑可复用。
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

    /** 平滑加权轮询状态（单线程访问，见 AdmissionScheduler 类的串行说明） */
    private final SmoothWeightedRoundRobin roundRobin = new SmoothWeightedRoundRobin();

    /** 决策临界区锁：保证「检查 → 放行」不被并发交错（文档 §9.2 第 3 条） */
    private final Object decisionLock = new Object();

    /** 最近一次同步后端容量的时间戳 */
    private volatile long capacitiesSyncedAt = 0L;

    /** 容量同步间隔：后台可能改容量，定期回源 */
    private static final long CAPACITY_SYNC_INTERVAL_MS = 5_000L;

    public AdmissionScheduler(AdmissionState state,
                              SchedulerConfigCache configCache,
                              BackendPoolService backendPoolService) {
        this.state = state;
        this.configCache = configCache;
        this.backendPoolService = backendPoolService;
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
        if (sessionId == null || sessionId.isBlank()) {
            return Decision.reject(0, "缺少 x-session-id");
        }
        if (keyHash == null || keyHash.isBlank()) {
            return Decision.reject(0, "缺少 API Key");
        }

        synchronized (decisionLock) {
            syncCapacitiesIfNeeded();

            // 0) 先判该 Key 是否还有被服务的资格 —— 这是终态判断，必须在「会话忙/槽位满」
            //    这些可重试判断之前：否则一个已停用的 Key 会收到 503 + Retry-After，
            //    客户端按指示无限重试，永远等不到成功，白白放大负载。
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
            for (BackendPool backend : candidates) {
                if (state.hasFreeSlot(backend.getBackendId())) {
                    available.add(backend);
                }
            }
            if (available.isEmpty()) {
                return Decision.reject(retryAfterSeconds(), "所有 GPU 后端已满");
            }

            // 3) 加权公平：选出本轮该由哪个 Key 得到服务机会
            if (weights.isEmpty()) {
                return Decision.reject(retryAfterSeconds(), "没有可用的 API Key");
            }

            String selectedKey = roundRobin.next(weights);
            if (selectedKey == null) {
                return Decision.reject(retryAfterSeconds(), "暂无可调度对象");
            }
            if (!selectedKey.equals(keyHash)) {
                // 本轮轮到别人 —— 但不要「白扣」本 Key 的权重，所以把这一轮让回去：
                // 重新走一次 next() 会让状态偏移，这里改为按权重比例给一个更短的 Retry-After，
                // 让客户端快速重试，长期仍收敛到 weight/totalWeight 的比例。
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
                // 极小概率：刚检查完就被别的线程抢走
                return Decision.reject(retryAfterSeconds(), "槽位竞争失败，稍后重试");
            }

            return Decision.admit(chosen, requestId);
        }
    }

    /**
     * 释放某次请求占用的会话与槽位。
     * <p>
     * <b>必须在流式响应彻底结束后调用</b>（{@code doFinally}），不能在收到响应头时调——
     * 否则 vLLM 还在生成，下一个请求就被放进去了，单并发被打破（文档 §9.2 第 6 条）。
     *
     * @param requestId 请求 id
     */
    public void release(String requestId) {
        state.release(requestId);
    }

    /**
     * 定期把后台配置的后端容量同步进 {@link AdmissionState}。
     * <p>
     * <b>为什么要先判「容量表为空就立即同步」</b>：{@code AdmissionState} 可能被
     * {@code reset()} 清空（测试、或运维在后台整体重建后端时），而节流基于时间戳，
     * 若清空后仍在节流窗口内，本轮决策读到的容量全是 0，会把所有请求判成「后端已满」
     * ——表现为「刚启动/刚清空后一段时间内，所有请求都 503」。
     * 因此容量表为空时必须跳过节流，立刻回源。
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
     * 思路：在途请求平均还要多久才会释放一个槽位。没有历史数据时给一个温和的默认值，
     * 保证客户端不会高频空转；上下限避免极端值。
     *
     * @return 建议重试秒数
     */
    private int retryAfterSeconds() {
        // 若所有槽位都在忙，估算「一个请求平均耗时」的一个保守切片
        int inflight = state.totalInflight();
        int estimate = Math.max(MIN_RETRY_AFTER_SECONDS, Math.min(MAX_RETRY_AFTER_SECONDS,
                inflight <= 0 ? 2 : 2 + inflight));
        return estimate;
    }

    /**
     * 本轮轮到别的 Key 时给一个更短的重试间隔。
     * <p>
     * 权重越高，说明它被选中的频率越高，等待应该越短：
     * {@code retryAfter = base * totalWeight / weight}，并夹在
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
