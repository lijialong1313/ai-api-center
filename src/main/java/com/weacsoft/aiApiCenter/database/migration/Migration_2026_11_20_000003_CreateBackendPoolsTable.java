package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 建 GPU 后端池表 {@code backend_pools}（文档 §5.3）。
 * <p>
 * {@code capacity} 对应槽位计数；{@code enabled=false} 的后端不参与调度。
 * {@code health_path} 默认 {@code /health}，探测结果写回 {@code last_health_status}。
 */
@MigrationAnnotation
public class Migration_2026_11_20_000003_CreateBackendPoolsTable implements Migration {

    static final String TABLE = "backend_pools";

    @Override
    public void up(Schema schema) {
        schema.create(TABLE, table -> {
            table.id();
            table.string("backend_id", 64).unique();
            table.string("name", 128).nullable();
            table.string("base_url", 512).notNull();
            table.integer("capacity").notNull().defaultValue(1);
            table.booleanColumn("enabled").notNull().defaultValue(true);
            table.string("health_path", 128).notNull().defaultValue("/health");
            table.string("last_health_status", 16).notNull().defaultValue("UNKNOWN");
            table.timestamp("last_checked_at").nullable();
            table.timestamps();
            // 索引随建表语句一并创建（Schema.create 会执行 blueprint 收集的索引 SQL）
            table.index("enabled");
        });
    }

    @Override
    public void down(Schema schema) {
        schema.dropIfExists(TABLE);
    }
}
