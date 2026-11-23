package com.weacsoft.aiApiCenter.app.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 按权重分配服务机会的公平闸门。
 * <p>
 * 配额制：将权重理解为长期服务比例。
 * <pre>
 *   已服务总数 t、本 Key 已服务 s、权重 w、权重之和 W
 *   理想值 ideal = (t + 1) * w / W
 *   s &lt; ideal  → 放行（尚未达到应得份额）
 *   否则        → 让路
 * </pre>
 * 理想值取 {@code t+1} 而非 {@code t}：本次服务计入统计。
 * <p>
 * 只要 {@code w &gt; 0} 的 Key 持续发请求，{@code t} 的增长会使 {@code ideal} 追上并超过
 * {@code s}，因此不存在长期得不到服务的情况。
 * <p>
 * 线程安全：仅由 {@code AdmissionScheduler} 的决策临界区调用，已串行化。
 */
@Component
public class WeightedFairnessGate {

    private static final Logger log = LoggerFactory.getLogger(WeightedFairnessGate.class);

    /** keyHash → 本窗口内已服务次数 */
    private final Map<String, Integer> served = new HashMap<>();

    /** 本窗口内总服务次数 */
    private int servedTotal = 0;

    /** 窗口起始时间 */
    private long windowStart = System.currentTimeMillis();

    /**
     * 统计窗口。
     * <p>
     * 配额制本身不需要窗口，窗口的作用是：权重调小后旧的高占比不会长期延续；
     * 同时避免 {@code servedTotal} 无限增长导致浮点精度下降。
     */
    static final long WINDOW_MILLIS = 60_000L;

    /**
     * 判断这次请求是否应被放行。
     * <p>
     * 放行时立即记账；若调用方随后未能真正转发（如槽位竞争失败），
     * 必须调用 {@link #rollback(String)} 归还额度。
     *
     * @param keyHash 本次请求的 Key 哈希
     * @param weights 参与公平分配的 Key → 权重（只含可用 Key）
     * @return 放行 true；该让路 false
     */
    public boolean allow(String keyHash, Map<String, Integer> weights) {
        if (keyHash == null || weights == null || weights.isEmpty()) {
            return true;   // 无公平信息时不阻塞请求
        }
        Integer myWeight = weights.get(keyHash);
        if (myWeight == null || myWeight <= 0) {
            return false;  // 不在名单里：不该服务（上层给出「已停用」终态）
        }

        rollWindowIfNeeded(weights);

        int totalWeight = 0;
        for (Integer w : weights.values()) {
            if (w != null && w > 0) {
                totalWeight += w;
            }
        }
        if (totalWeight <= 0) {
            return true;
        }

        int myServed = served.getOrDefault(keyHash, 0);
        // 理想服务次数：把本次也算进去（t+1），否则每个 Key 都差一步、开头全被拒
        double ideal = (servedTotal + 1.0) * myWeight / totalWeight;

        if (myServed < ideal) {
            served.merge(keyHash, 1, Integer::sum);
            servedTotal++;
            return true;
        }
        return false;
    }

    /**
     * 回滚一次记账（放行后实际未能转发时调用）。
     *
     * @param keyHash 该请求的 Key 哈希
     */
    public void rollback(String keyHash) {
        if (keyHash == null) {
            return;
        }
        Integer current = served.get(keyHash);
        if (current != null && current > 0) {
            served.put(keyHash, current - 1);
            if (servedTotal > 0) {
                servedTotal--;
            }
        }
    }

    /**
     * 窗口到期则清零重新统计。
     *
     * @param weights 当前权重表（仅用于日志）
     */
    private void rollWindowIfNeeded(Map<String, Integer> weights) {
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MILLIS) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[fairness] 窗口重置 served={} total={} weights={}",
                    served, servedTotal, weights);
        }
        windowStart = now;
        served.clear();
        servedTotal = 0;
    }

    /** 当前窗口的服务统计快照（运维排查用） */
    public Map<String, Integer> snapshot() {
        return new HashMap<>(served);
    }

    /** 清空状态（测试用） */
    public void reset() {
        windowStart = System.currentTimeMillis();
        served.clear();
        servedTotal = 0;
    }
}
