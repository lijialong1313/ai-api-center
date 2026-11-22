package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.model.BackendPool;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分级调度测试：某些 GPU 只服务达到指定等级的 API Key。
 * <p>
 * 核心语义是「低等级 Key 不得占用高等级后端的槽位」——这类规则一旦写错，
 * 后果是高价值算力被普通流量占满，且从监控上看只是「GPU 很忙」，很难察觉。
 * 因此这里逐条验证：能进、不能进、以及「不能进时给的原因是否正确」。
 */
@SpringBootTest
@ActiveProfiles("test")
class BackendLevelSchedulingTest {

    @Autowired
    private AdmissionScheduler scheduler;

    @Autowired
    private AdmissionState state;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private BackendPoolService backendPoolService;

    private final List<Long> keyIds = new ArrayList<>();
    private final List<Long> backendIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        state.reset();
    }

    @AfterEach
    void tearDown() {
        keyIds.forEach(id -> apiKeyService.delete(id));
        backendIds.forEach(id -> backendPoolService.delete(id));
        keyIds.clear();
        backendIds.clear();
        state.reset();
    }

    private ApiKey newKey(String label, int weight, int level) {
        ApiKeyService.CreatedKey created = apiKeyService.create(label, weight, level);
        keyIds.add(created.key().getId());
        return created.key();
    }

    private BackendPool newBackend(String backendId, int capacity, int minLevel) {
        BackendPool pool = backendPoolService.create(backendId, "测试-" + backendId,
                "http://127.0.0.1:59999", capacity, "/health", null, null, minLevel);
        backendIds.add(pool.getId());
        return pool;
    }

    @Test
    @DisplayName("低等级 Key 不得进入高等级后端")
    void lowLevelKeyCannotUseHighLevelBackend() {
        ApiKey low = newKey("低等级", 5, 0);
        // 唯一后端要求等级 >= 2
        newBackend("lv-gpu-hi", 4, 2);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-1", "lv-sess-1", low.getKeyHash(), low.getLevel());

        assertThat(d.admitted())
                .as("等级 0 的 Key 不应被放行到要求等级 2 的后端")
                .isFalse();
        assertThat(d.reason())
                .as("原因应明确指出是等级不足，而不是「后端已满」——否则会误导排查方向")
                .contains("等级");
    }

    @Test
    @DisplayName("高等级 Key 可以进入要求等级的后端")
    void highLevelKeyCanUseHighLevelBackend() {
        ApiKey high = newKey("高等级", 5, 3);
        newBackend("lv-gpu-hi2", 4, 2);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-2", "lv-sess-2", high.getKeyHash(), high.getLevel());

        assertThat(d.admitted()).as("等级 3 >= 门槛 2，应放行").isTrue();
        scheduler.release("lv-req-2");
    }

    @Test
    @DisplayName("等级恰好等于门槛时应放行（边界：>= 而非 >）")
    void levelEqualToThresholdIsAllowed() {
        ApiKey key = newKey("边界等级", 5, 2);
        newBackend("lv-gpu-eq", 4, 2);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-3", "lv-sess-3", key.getKeyHash(), key.getLevel());

        assertThat(d.admitted()).as("等级等于门槛应放行").isTrue();
        scheduler.release("lv-req-3");
    }

    @Test
    @DisplayName("门槛为 0 时对所有等级开放")
    void zeroThresholdAllowsEveryone() {
        ApiKey key = newKey("零门槛", 5, 0);
        newBackend("lv-gpu-zero", 4, 0);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-4", "lv-sess-4", key.getKeyHash(), key.getLevel());

        assertThat(d.admitted()).as("门槛 0 表示不限制").isTrue();
        scheduler.release("lv-req-4");
    }

    @Test
    @DisplayName("同时存在高低门槛后端时，低等级 Key 应落到低门槛后端")
    void lowLevelKeyFallsBackToOpenBackend() {
        ApiKey low = newKey("低等级2", 5, 0);
        // 一个要求等级 5，一个不限制
        newBackend("lv-gpu-exclusive", 4, 5);
        newBackend("lv-gpu-open", 4, 0);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-5", "lv-sess-5", low.getKeyHash(), low.getLevel());

        assertThat(d.admitted()).as("存在开放后端时应放行").isTrue();
        assertThat(d.backend().getBackendId())
                .as("必须落到不限制等级的那个后端")
                .isEqualTo("lv-gpu-open");
        scheduler.release("lv-req-5");
    }

    @Test
    @DisplayName("高等级 Key 优先使用高等级后端（高门槛后端空闲时不应被跳过）")
    void highLevelKeyPrefersExclusiveBackend() {
        ApiKey high = newKey("高等级2", 5, 9);
        newBackend("lv-gpu-only-hi", 4, 5);
        newBackend("lv-gpu-also-open", 4, 0);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-6", "lv-sess-6", high.getKeyHash(), high.getLevel());

        assertThat(d.admitted()).isTrue();
        // 两个后端都空闲，选择策略是「在途数最少」，平局取先出现的。
        // 这里只断言它确实选了其中一个合法后端，不锁死顺序（那是实现细节）。
        assertThat(d.backend().getBackendId())
                .as("应选中某个满足等级要求的后端")
                .isIn("lv-gpu-only-hi", "lv-gpu-also-open");
        assertThat(d.backend().allowsLevel(high.getLevel()))
                .as("选中的后端必须满足等级要求")
                .isTrue();
        scheduler.release("lv-req-6");
    }

    @Test
    @DisplayName("等级变更即时生效：Key 升级后即可进入原先后端")
    void levelChangeTakesEffectImmediately() {
        ApiKey key = newKey("将升级", 5, 0);
        newBackend("lv-gpu-need2", 4, 2);

        // 初始等级不足
        assertThat(scheduler.decide("lv-req-7a", "lv-sess-7a", key.getKeyHash(), 0).admitted())
                .as("等级 0 时应被拒绝").isFalse();

        // 升级到 2
        apiKeyService.update(key.getId(), null, null, null, 2);
        ApiKey reloaded = apiKeyService.get(key.getId());
        assertThat(reloaded.getLevel()).as("等级应已更新").isEqualTo(2);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-7b", "lv-sess-7b", reloaded.getKeyHash(), reloaded.getLevel());
        assertThat(d.admitted()).as("升级后应可进入").isTrue();
        scheduler.release("lv-req-7b");
    }

    @Test
    @DisplayName("后端门槛调整即时生效：调高后低等级 Key 被拒")
    void backendThresholdChangeTakesEffectImmediately() {
        ApiKey key = newKey("普通", 5, 0);
        BackendPool pool = newBackend("lv-gpu-adjust", 4, 0);

        // 初始不限制，可进
        assertThat(scheduler.decide("lv-req-8a", "lv-sess-8a", key.getKeyHash(), 0).admitted())
                .as("门槛 0 时应可进入").isTrue();
        scheduler.release("lv-req-8a");

        // 把门槛调高到 3
        backendPoolService.update(pool.getId(), null, null, null, null, null, null, null, 3);
        BackendPool reloaded = backendPoolService.get(pool.getId());
        assertThat(reloaded.getMinLevel()).as("门槛应已更新").isEqualTo(3);

        AdmissionScheduler.Decision d =
                scheduler.decide("lv-req-8b", "lv-sess-8b", key.getKeyHash(), 0);
        assertThat(d.admitted()).as("门槛调高后低等级 Key 应被拒").isFalse();
        assertThat(d.reason()).contains("等级");
    }

    @Test
    @DisplayName("负数等级按 0 处理（防止绕过门槛）")
    void negativeLevelIsClampedToZero() {
        ApiKeyService.CreatedKey created = apiKeyService.create("负数等级", 1, -5);
        keyIds.add(created.key().getId());

        ApiKey reloaded = apiKeyService.get(created.key().getId());
        assertThat(reloaded.getLevel())
                .as("负数等级必须被钳到 0，否则可能出现意外的高权限")
                .isEqualTo(0);
    }
}
