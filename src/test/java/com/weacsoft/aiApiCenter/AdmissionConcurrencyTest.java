package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.scheduler.AdmissionScheduler;
import com.weacsoft.aiApiCenter.app.scheduler.AdmissionState;
import com.weacsoft.aiApiCenter.app.scheduler.SmoothWeightedRoundRobin;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import com.weacsoft.aiApiCenter.app.service.BackendPoolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 准入调度层的并发正确性测试。
 * <p>
 * 设计文档 §9.1 的六条不变量里，前两条是并发相关的硬约束：
 * <ol>
 *   <li>同一 {@code sessionId} 任意时刻最多一个请求处于「转发到 vLLM」状态；</li>
 *   <li>每个后端在途请求数不超过其 {@code capacity}。</li>
 * </ol>
 * 这两条一旦被击穿，后果是 vLLM 被并发打爆（{@code max_num_seqs=1} 时直接 OOM 或排队超时）。
 * 单线程测试无法发现竞态，所以这里全部用<b>真实线程池</b>并发打，并在断言里直接读
 * 「峰值同时在途数」——不是看最终状态，而是看过程中有没有越界过。
 */
@SpringBootTest
class AdmissionConcurrencyTest {

    @Autowired
    private AdmissionState state;

    @Autowired
    private AdmissionScheduler scheduler;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private BackendPoolService backendPoolService;

    private final List<Long> createdKeyIds = new ArrayList<>();
    private final List<Long> createdBackendIds = new ArrayList<>();

    @BeforeEach
    void reset() {
        state.reset();
    }

    @AfterEach
    void cleanup() {
        createdKeyIds.forEach(id -> apiKeyService.delete(id));
        createdBackendIds.forEach(id -> backendPoolService.delete(id));
        createdKeyIds.clear();
        createdBackendIds.clear();
        state.reset();
    }

    /** 建一个 Key 并登记以便清理 */
    private ApiKeyService.CreatedKey newKey(String label, int weight) {
        ApiKeyService.CreatedKey created = apiKeyService.create(label, weight);
        createdKeyIds.add(created.key().getId());
        return created;
    }

    /** 建一个后端并登记以便清理 */
    private String newBackend(String backendId, int capacity) {
        var pool = backendPoolService.create(backendId, "测试-" + backendId,
                "http://127.0.0.1:59999", capacity, "/health");
        // create 可能因 backendId 冲突返回已有记录，取真实 id
        var actual = backendPoolService.get(pool.getId());
        createdBackendIds.add(actual.getId());
        return actual.getBackendId();
    }

    // ==================== 核心不变量 1：会话串行 ====================

    @Test
    @DisplayName("同一 sessionId 并发冲击：任意时刻最多 1 个请求被放行（会话严格串行）")
    void sameSessionNeverRunsConcurrently() throws Exception {
        ApiKeyService.CreatedKey key = newKey("会话串行测试", 10);
        newBackend("conc-session-gpu", 4);   // 容量给大，确保拒绝只可能来自会话锁

        int threads = 32;
        String sharedSession = "session-shared-001";
        AtomicInteger admittedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicInteger concurrentInside = new AtomicInteger();
        AtomicInteger peakConcurrent = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    String requestId = "req-" + System.nanoTime() + "-" + Thread.currentThread().getId();
                    AdmissionScheduler.Decision decision =
                            scheduler.decide(requestId, sharedSession, key.key().getKeyHash());
                    if (decision.admitted()) {
                        int now = concurrentInside.incrementAndGet();
                        peakConcurrent.accumulateAndGet(now, Math::max);
                        admittedCount.incrementAndGet();
                        // 持有槽位一段时间：制造「其他线程在此期间尝试进入」的重叠窗口。
                        // 必须够长（>> 线程调度抖动），否则其他线程在放行者已 release 后才跑到，
                        // 看到的都是空闲状态，测不出并发违约。
                        Thread.sleep(60);
                        concurrentInside.decrementAndGet();
                        scheduler.release(requestId);
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 记录为拒绝
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("并发任务应在 30s 内完成").isTrue();
        pool.shutdownNow();

        assertThat(peakConcurrent.get())
                .as("同一 session 的峰值同时在途数必须为 1，否则会话串行被击穿")
                .isEqualTo(1);
        assertThat(admittedCount.get()).as("应有请求被放行").isGreaterThan(0);
        assertThat(admittedCount.get() + rejectedCount.get()).as("每个请求都应有着落")
                .isEqualTo(threads);
    }

    // ==================== 核心不变量 2：后端容量 ====================

    @Test
    @DisplayName("多会话并发冲击单容量后端：峰值在途数不得超过 capacity")
    void backendCapacityNeverExceeded() throws Exception {
        // 多个不同 Key 且权重相同，排除加权轮询造成的拒绝
        List<ApiKeyService.CreatedKey> keys = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            keys.add(newKey("容量测试-" + i, 1));
        }
        newBackend("conc-cap-gpu", 2);   // 容量 2

        int threads = 40;
        AtomicInteger concurrentInside = new AtomicInteger();
        AtomicInteger peakConcurrent = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    // 每个线程用各自独立的 session，避免撞上会话锁
                    String sessionId = "sess-" + idx;
                    String reqId = "cap-req-" + idx;
                    AirKeyHolder holder = new AirKeyHolder(keys.get(idx % keys.size()).key().getKeyHash());
                    AdmissionScheduler.Decision decision =
                            scheduler.decide(reqId, sessionId, holder.keyHash());
                    if (decision.admitted()) {
                        int now = concurrentInside.incrementAndGet();
                        peakConcurrent.accumulateAndGet(now, Math::max);
                        admitted.incrementAndGet();
                        Thread.sleep(60);
                        concurrentInside.decrementAndGet();
                        scheduler.release(reqId);
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(40, TimeUnit.SECONDS)).as("并发任务应在 40s 内完成").isTrue();
        pool.shutdownNow();

        assertThat(peakConcurrent.get())
                .as("后端峰值在途数不得超过 capacity=2，否则 vLLM 会被打爆")
                .isLessThanOrEqualTo(2);
        assertThat(admitted.get()).as("应有请求被放行").isGreaterThan(0);
        assertThat(rejected.get()).as("容量满时应产生拒绝（503 来源）").isGreaterThan(0);
    }

    /** 小工具：把 keyHash 包一层，便于 lambda 里捕获 effectively-final 值 */
    private record AirKeyHolder(String keyHash) {
    }

    // ==================== 释放的正确性 ====================

    @Test
    @DisplayName("释放后槽位与会话都应立刻可用（不会泄漏导致永久拒绝）")
    void releaseFreesBothSessionAndSlot() {
        ApiKeyService.CreatedKey key = newKey("释放测试", 5);
        String backendId = newBackend("conc-release-gpu", 1);
        String sessionId = "sess-release";

        AdmissionScheduler.Decision first = scheduler.decide("r1", sessionId, key.key().getKeyHash());
        assertThat(first.admitted()).as("首次应放行").isTrue();
        assertThat(state.isSessionBusy(sessionId)).isTrue();
        assertThat(state.inflightOf(backendId)).isEqualTo(1);

        // 未释放前，同会话再请求必须被拒
        AdmissionScheduler.Decision second = scheduler.decide("r2", sessionId, key.key().getKeyHash());
        assertThat(second.admitted()).as("同会话未释放时应拒绝").isFalse();

        scheduler.release("r1");
        assertThat(state.isSessionBusy(sessionId)).as("释放后会话应空闲").isFalse();
        assertThat(state.inflightOf(backendId)).as("释放后槽位应归还").isZero();

        AdmissionScheduler.Decision third = scheduler.decide("r3", sessionId, key.key().getKeyHash());
        assertThat(third.admitted()).as("释放后应能再次放行").isTrue();
        scheduler.release("r3");
    }

    @Test
    @DisplayName("重复释放不会把在途计数减成负数")
    void doubleReleaseIsIdempotent() {
        ApiKeyService.CreatedKey key = newKey("幂等释放", 5);
        String backendId = newBackend("conc-idem-gpu", 2);

        AdmissionScheduler.Decision d = scheduler.decide("ridem", "sess-idem", key.key().getKeyHash());
        assertThat(d.admitted()).isTrue();

        scheduler.release("ridem");
        scheduler.release("ridem");
        scheduler.release("ridem");

        assertThat(state.inflightOf(backendId)).as("重复释放后仍应为 0，不能变负").isZero();
    }

    @Test
    @DisplayName("缺少 x-session-id 必须拒绝（文档 §9.2 第 7 条）")
    void missingSessionIdIsRejected() {
        ApiKeyService.CreatedKey key = newKey("无会话测试", 5);
        newBackend("conc-nosess-gpu", 2);

        assertThat(scheduler.decide("r-nosess", null, key.key().getKeyHash()).admitted()).isFalse();
        assertThat(scheduler.decide("r-nosess2", "  ", key.key().getKeyHash()).admitted()).isFalse();
    }

    @Test
    @DisplayName("Key 停用后不再被调度（权重表即时生效）")
    void disabledKeyIsNotScheduled() {
        ApiKeyService.CreatedKey key = newKey("停用测试", 5);
        newBackend("conc-disabled-gpu", 2);

        assertThat(scheduler.decide("r-before", "sess-d1", key.key().getKeyHash()).admitted()).isTrue();
        scheduler.release("r-before");

        apiKeyService.setEnabled(key.key().getId(), false);

        AdmissionScheduler.Decision after =
                scheduler.decide("r-after", "sess-d2", key.key().getKeyHash());
        assertThat(after.admitted()).as("停用后不应再放行").isFalse();
        assertThat(after.retryAfterSeconds()).as("停用不该让客户端重试（应直接失败）").isEmpty();
    }
}
