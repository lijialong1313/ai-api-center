package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 建队列任务表 {@code jobs} 与失败任务表 {@code failed_jobs}。
 * <p>
 * 这两张表由 jaravel-vendor 的 {@code queue-database} 模块的
 * {@code DatabaseQueueDriver} 读写。该模块自带的建表迁移是以
 * {@code .java} 源文件形式放在其 jar 的资源目录里的，并非编译后的类：
 * 本应用使用 {@code jaravel.migration.source=CLASSPATH}，扫描器只识别
 * 编译后的迁移类，且 Spring Boot 可执行包里 {@code BOOT-INF/lib/} 下的
 * 依赖 jar 会被整体跳过，因此该内置迁移在开发与打包两种运行时都不会被自动执行。
 * <p>
 * 为保证 {@code jaravel.queue.driver=database} 在任何部署形态下（含
 * 单机、无 Redis）开箱可用，这里由应用侧声明同一结构的建表迁移，
 * 放在应用自身的 {@code BOOT-INF/classes/} 内，从而在启动的
 * {@code auto-run} 阶段可靠执行。
 * <p>
 * 列结构与 vendor 模块 {@code DatabaseQueueDriver#defineJobsTable} /
 * {@code defineFailedJobsTable} 保持一致，建表走方言感知的
 * {@link Schema#createIfAbsent}，表已存在时跳过，重复启动不报错。
 * 本迁移在 {@code driver=sync} 时同样执行，只会产生两张空表，
 * 因此运行期在 {@code sync} 与 {@code database} 之间切换无需额外建表步骤。
 */
@MigrationAnnotation
public class Migration_2026_11_24_000001_CreateQueueTables implements Migration {

    static final String JOBS_TABLE = "jobs";
    static final String FAILED_JOBS_TABLE = "failed_jobs";

    @Override
    public void up(Schema schema) {
        schema.createIfAbsent(JOBS_TABLE, table -> {
            table.id();
            table.string("queue", 255);
            table.text("payload");
            table.integer("attempts").defaultValue(0);
            table.bigInteger("reserved_at").nullable();
            table.bigInteger("available_at");
            table.bigInteger("created_at");
            table.index("queue");
        });

        schema.createIfAbsent(FAILED_JOBS_TABLE, table -> {
            table.id();
            table.string("queue", 255);
            table.text("payload");
            table.text("exception").nullable();
            table.integer("attempts").defaultValue(0);
            table.bigInteger("failed_at");
            table.index("queue");
        });
    }

    @Override
    public void down(Schema schema) {
        schema.dropIfExists(FAILED_JOBS_TABLE);
        schema.dropIfExists(JOBS_TABLE);
    }
}
