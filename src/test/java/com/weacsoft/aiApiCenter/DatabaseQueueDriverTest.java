package com.weacsoft.aiApiCenter;

import com.weacsoft.jaravel.vendor.core.queue.QueuedJob;
import com.weacsoft.jaravel.vendor.queue.database.DatabaseQueueDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * database 队列驱动在单机、无 Redis 条件下的可用性。
 * <p>
 * 验证两点：
 * <ol>
 *   <li>{@code jaravel.queue.driver=database} 且未启用任何 Redis 时，
 *       上下文能够正常启动，且装配出 {@link DatabaseQueueDriver}；</li>
 *   <li>应用自带的迁移在启动迁移阶段已建好 {@code jobs} / {@code failed_jobs}，
 *       推送、弹出、删除的完整循环可执行。
 * </ol>
 * 本测试类通过 {@code @TestPropertySource} 将驱动固定为 {@code database}，
 * 与其他测试类使用相互独立的 Spring 上下文与同一测试库文件。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "jaravel.queue.driver=database")
class DatabaseQueueDriverTest {

    @Autowired
    private DatabaseQueueDriver databaseQueueDriver;

    @Test
    @DisplayName("无 Redis 时 database 驱动被装配")
    void driverIsWiredWithoutRedis() {
        assertThat(databaseQueueDriver).isNotNull();
        assertThat(databaseQueueDriver.getTable()).isEqualTo("jobs");
        assertThat(databaseQueueDriver.getFailedTable()).isEqualTo("failed_jobs");
    }

    @Test
    @DisplayName("队列表在启动迁移中已创建，推送与弹出可用")
    void pushPopDeleteRoundTrip() {
        String queue = "queue_driver_test";
        databaseQueueDriver.clear(queue);

        long id = databaseQueueDriver.push(queue, "{\"action\":\"self-test\"}");
        assertThat(id).isGreaterThan(0);
        assertThat(databaseQueueDriver.size(queue)).isEqualTo(1);

        QueuedJob job = databaseQueueDriver.pop(queue);
        assertThat(job).isNotNull();
        assertThat(job.getId()).isEqualTo(id);
        assertThat(job.getQueue()).isEqualTo(queue);
        assertThat(job.getPayload()).isEqualTo("{\"action\":\"self-test\"}");

        databaseQueueDriver.delete(job.getId());
        assertThat(databaseQueueDriver.size(queue)).isZero();
    }
}
