package com.weacsoft.aiApiCenter.app.scheduler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 平滑加权轮询（Smooth Weighted Round-Robin），对应设计文档 §4.3。
 * <p>
 * 算法（nginx 的 smooth WRR）：
 * <ol>
 *   <li>对每个参与本轮选择的 Key：{@code current += weight}；</li>
 *   <li>选出 {@code current} 最大的 Key（平局按加入顺序，即 FIFO 公平）；</li>
 *   <li>被选中的 Key：{@code current -= totalWeight}。</li>
 * </ol>
 * 效果：权重 VIP=5 / 普通=1 时，放行序列近似 {@code VIP, VIP, 普通, VIP, VIP, 普通, ...}，
 * VIP 拿到约 5 倍服务机会，普通有保底、不会被饿死。
 * <p>
 * <b>为什么不用「按比例随机」</b>：随机会让低权重 Key 在短窗口内饿死（连续多次抽不到），
 * 而平滑 WRR 保证任何权重 &gt; 0 的 Key 在 {@code totalWeight} 轮内必被选中一次。
 * <p>
 * <b>会话阻塞的 Key 不参与本轮</b>（文档 §9.2 第 4 条）：如果某 Key 的请求全部因会话
 * in-flight 而无法放行，把它排除在 activeKeys 之外，否则它的 current 会持续累积，
 * 等会话一空闲就连续霸占——这正是「权重累积导致后续霸占」的成因。
 * <p>
 * <b>线程安全</b>：单实例内由调度循环单线程调用（{@link AdmissionScheduler} 保证串行），
 * 本类不做同步；{@code currentWeights} 是纯内存状态，不跨实例共享——
 * 多实例部署时每个实例各自维护自己的 current，这是有意的：
 * 全局公平性由「队列本身是共享的」+「每个请求都要抢队列」保证，不需要分布式一致的 current。
 */
public class SmoothWeightedRoundRobin {

    /** Key 标识 → 当前累计权重。只由调度线程访问，无需并发容器。 */
    private final Map<String, Integer> currentWeights = new LinkedHashMap<>();

    /**
     * 从候选中选出一个 Key。
     * <p>
     * 每次调用都会更新内部 {@code currentWeights}，因此<b>必须只在一个串行的调度循环里调用</b>。
     *
     * @param weights 本轮参与选择的 Key → 权重（权重必须 &gt; 0；&lt;= 0 的项会被忽略）
     * @return 选中的 Key；候选为空时返回 {@code null}
     */
    public String next(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return null;
        }

        // 清掉已经不在候选里的 Key，避免其 current 残留影响后续轮次
        currentWeights.keySet().retainAll(weights.keySet());

        int totalWeight = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            int weight = entry.getValue() == null ? 0 : entry.getValue();
            if (weight <= 0) {
                continue;
            }
            totalWeight += weight;
            currentWeights.merge(entry.getKey(), weight, Integer::sum);
        }
        if (totalWeight <= 0) {
            return null;
        }

        // 选 current 最大者；平局时保留先出现的（LinkedHashMap 顺序 = 加入顺序 = FIFO 公平）
        String selected = null;
        int max = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> entry : currentWeights.entrySet()) {
            int current = entry.getValue() == null ? 0 : entry.getValue();
            if (current > max) {
                max = current;
                selected = entry.getKey();
            }
        }
        if (selected == null) {
            return null;
        }

        // 被选中者扣减总权重，使长期比例趋于 weight/totalWeight
        currentWeights.merge(selected, -totalWeight, Integer::sum);
        return selected;
    }

    /**
     * 从候选中直接选出被放行的 Key 集合（按轮询顺序展开）。
     * <p>
     * 用于「一次调度轮次里放行多个请求」的场景：按顺序连续调用 {@link #next}，
     * 每次从 weights 里剔除已被选中的 Key（同一个 Key 在同一轮只放行一个请求，
     * 因为它自己的队列是 FIFO 的，放一个就够了）。
     *
     * @param weights     候选 Key → 权重
     * @param maxSelections 最多选几个
     * @return 按放行顺序排列的 Key 列表
     */
    public Set<String> selectBatch(Map<String, Integer> weights, int maxSelections) {
        Set<String> selected = new LinkedHashSet<>();
        if (weights == null || weights.isEmpty() || maxSelections <= 0) {
            return selected;
        }
        Map<String, Integer> remaining = new LinkedHashMap<>(weights);
        while (selected.size() < maxSelections && !remaining.isEmpty()) {
            String key = next(remaining);
            if (key == null) {
                break;
            }
            selected.add(key);
            remaining.remove(key);
        }
        return selected;
    }

    /** 当前累计权重快照（测试与运维排查用）。 */
    public Map<String, Integer> snapshot() {
        return new LinkedHashMap<>(currentWeights);
    }

    /** 清空状态（测试用）。 */
    public void reset() {
        currentWeights.clear();
    }
}
