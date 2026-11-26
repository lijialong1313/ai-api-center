package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.scheduler.AdmissionScheduler;
import com.weacsoft.aiApiCenter.app.scheduler.AdmissionState;
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
 * 覆盖设计文档 §9.1 前两条并发不变量：
 * <ol>
 *   <li>同一 {@code sessionId} 任意时刻最多一个请求处于「转发到 vLLM」状态；</li>
 *   <li>每个后端在途请求数不超过其 {@code capacity}。</li>
 * </ol>
 * 使用真实线程池并发调用，断言读取峰值同时在途数，而非最终状态。
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
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
                        // 持有槽位 60ms：制造其他线程在此期间尝试进入的重叠窗口，
                        // 窗口需明显大于线程调度抖动，否则测不出并发违约。
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
                .as("同一 session 的峰值同时在途数必须为 1")
                .isEqualTo(1);
        assertThat(admittedCount.get()).as("应有请求被放行").isGreaterThan(0);
        assertThat(admittedCount.get() + rejectedCount.get()).as("每个请求都应有着落")
                .isEqualTo(threads);
    }

    @Test
    @DisplayName("会话并发上限 3：同一 session 峰值同时在途不超过 3，且能并行放行 3 个")
    void sessionConcurrencyLimitAllowsThreeParallel() throws Exception {
        // 建 Key 后把并发上限改成 3（update 会同时清掉调度器缓存，立即生效）
        ApiKeyService.CreatedKey created = newKey("会话并发测试", 10);
        apiKeyService.update(created.key().getId(), null, null, null, null, 3);
        String keyHash = created.key().getKeyHash();
        newBackend("conc-limit-gpu", 4);   // 容量给大，确保拒绝只可能来自会话上限

        int threads = 32;
        String sharedSession = "session-limit-001";
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
                            scheduler.decide(requestId, sharedSession, keyHash);
                    if (decision.admitted()) {
                        int now = concurrentInside.incrementAndGet();
                        peakConcurrent.accumulateAndGet(now, Math::max);
                        admittedCount.incrementAndGet();
                        Thread.sleep(60);
                        concurrentInside.decrementAndGet();
                        scheduler.release(requestId);
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("并发任务应在 30s 内完成").isTrue();
        pool.shutdownNow();

        assertThat(peakConcurrent.get())
                .as("同一 session 的峰值同时在途数不得超过并发上限 3")
                .isLessThanOrEqualTo(3);
        assertThat(peakConcurrent.get())
                .as("3 个名额应能被同时占用（不再串行）")
                .isGreaterThanOrEqualTo(3);
        assertThat(admittedCount.get()).as("应有请求被放行").isGreaterThan(0);
        assertThat(admittedCount.get() + rejectedCount.get()).as("每个请求都应有着落")
                .isEqualTo(threads);
    }

    @Test
    @DisplayName("后台把会话并发上限从 1 改成 2 后，同一 session 的第二个请求即被放行")
    void concurrencyUpdateTakesEffectImmediately() {
        ApiKeyService.CreatedKey created = newKey("即时生效测试", 5);
        String keyHash = created.key().getKeyHash();
        String backendId = newBackend("conc-update-gpu", 2);
        String sessionId = "sess-update";

        // 初始并发上限 1：同会话第二个请求被拒
        AdmissionScheduler.Decision r1 = scheduler.decide("u-r1", sessionId, keyHash);
        assertThat(r1.admitted()).isTrue();
        AdmissionScheduler.Decision r2 = scheduler.decide("u-r2", sessionId, keyHash);
        assertThat(r2.admitted()).as("上限 1 时第二个请求应被拒").isFalse();

        // 后台改上限为 2（service 层会失效调度器缓存）
        apiKeyService.update(created.key().getId(), null, null, null, null, 2);

        AdmissionScheduler.Decision r3 = scheduler.decide("u-r3", sessionId, keyHash);
        assertThat(r3.admitted()).as("上限改 2 后，同会话第二个在途请求应放行").isTrue();
        assertThat(state.inflightOf(backendId)).isEqualTo(2);

        scheduler.release("u-r1");
        scheduler.release("u-r3");
        assertThat(state.sessionInflight(sessionId)).as("两个都释放后会话在途归零").isZero();
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
                .as("后端峰值在途数不得超过 capacity=2")
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

        // 未释放前，同会话的再次请求应被拒绝
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
    @DisplayName("会话路径：空 sessionId 由 decide 拒绝（网关层已改走 decideAnonymous）")
    void emptySessionIdStillRejectedBySessionPath() {
        ApiKeyService.CreatedKey key = newKey("无会话测试", 5);
        newBackend("conc-nosess-gpu", 2);

        assertThat(scheduler.decide("r-nosess", null, key.key().getKeyHash()).admitted()).isFalse();
        assertThat(scheduler.decide("r-nosess2", "  ", key.key().getKeyHash()).admitted()).isFalse();
    }

    // ==================== 匿名（无 x-session-id）路径 ====================

    @Test
    @DisplayName("匿名请求：有空槽时立即放行，且只占槽位不占会话")
    void anonymousAdmitsWhenSlotFree() {
        ApiKeyService.CreatedKey key = newKey("匿名放行", 5);
        String backendId = newBackend("conc-anon-gpu", 1);

        AdmissionScheduler.Decision d =
                scheduler.decideAnonymous("r-anon-1", key.key().getKeyHash(), 0);
        assertThat(d.admitted()).as("有空槽的匿名请求应立即放行").isTrue();
        assertThat(state.isSessionBusy("any-session")).as("匿名请求不登记会话").isFalse();
        assertThat(state.inflightOf(backendId)).as("匿名请求占一个后端槽位").isEqualTo(1);

        scheduler.release("r-anon-1");
        assertThat(state.inflightOf(backendId)).isZero();
    }

    @Test
    @DisplayName("匿名请求：槽位满时拒绝且 Retry-After 恒为 0（不进排队，网关映射 429）")
    void anonymousRejectsWithoutRetryAfterWhenFull() {
        ApiKeyService.CreatedKey key = newKey("匿名满槽", 5);
        String backendId = newBackend("conc-anon-full", 1);

        // 先用一个会话型请求占满唯一槽位
        AdmissionScheduler.Decision holder = scheduler.decide("r-holder", "sess-holder", key.key().getKeyHash());
        assertThat(holder.admitted()).isTrue();

        AdmissionScheduler.Decision d =
                scheduler.decideAnonymous("r-anon-2", key.key().getKeyHash(), 0);
        assertThat(d.admitted()).as("槽位满时匿名请求应被拒").isFalse();
        assertThat(d.retryAfterSeconds()).as("匿名拒绝不引导重试（网关映射 429）").isEmpty();

        scheduler.release("r-holder");
    }

    @Test
    @DisplayName("匿名请求：Key 停用时拒绝且 Retry-After 为 0（网关映射 403）")
    void anonymousDisabledKeyIsTerminal() {
        ApiKeyService.CreatedKey key = newKey("匿名停用", 5);
        newBackend("conc-anon-disabled", 2);

        apiKeyService.setEnabled(key.key().getId(), false);

        AdmissionScheduler.Decision d =
                scheduler.decideAnonymous("r-anon-3", key.key().getKeyHash(), 0);
        assertThat(d.admitted()).isFalse();
        assertThat(d.retryAfterSeconds()).isEmpty();
        assertThat(d.reason()).isEqualTo(AdmissionScheduler.REASON_KEY_DISABLED);
    }

    @Test
    @DisplayName("匿名请求：多个匿名可并行占满各槽位，峰值在途数不超过 capacity")
    void anonymousBurstRespectsCapacity() throws Exception {
        ApiKeyService.CreatedKey key = newKey("匿名并发", 5);
        String backendId = newBackend("conc-anon-burst", 3);   // 容量 3

        int threads = 12;
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    AdmissionScheduler.Decision d = scheduler.decideAnonymous(
                            "r-burst-" + idx, key.key().getKeyHash(), 0);
                    if (d.admitted()) {
                        admitted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(state.inflightOf(backendId)).as("在途数不得超过 capacity=3").isLessThanOrEqualTo(3);
        assertThat(admitted.get()).as("应有请求被放行").isGreaterThan(0);
        assertThat(rejected.get()).as("槽位用尽后应开始拒绝").isGreaterThan(0);
        assertThat(admitted.get() + rejected.get()).isEqualTo(threads);
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
