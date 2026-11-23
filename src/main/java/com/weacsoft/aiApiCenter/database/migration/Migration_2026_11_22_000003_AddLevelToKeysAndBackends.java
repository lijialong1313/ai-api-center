package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 为 API Key 与 GPU 后端增加「等级」字段，支持分级调度。
 * <p>
 * 部分 GPU（算力更强或显存更大的机器）只服务高等级 Key，其余 GPU 对全体开放：
 * <ul>
 *   <li>{@code api_keys.level} —— Key 的等级，默认 0；</li>
 *   <li>{@code backend_pools.min_level} —— 该后端要求的最低 Key 等级，默认 0（不限制）。</li>
 * </ul>
 * 调度时按 {@code key.level >= backend.min_level} 过滤后端，数值越大等级越高。
 * 采用阈值而非等级白名单：等级有序，新增等级时无需逐端补配。
 */
@MigrationAnnotation
public class Migration_2026_11_22_000003_AddLevelToKeysAndBackends implements Migration {

    static final String KEYS = "api_keys";
    static final String BACKENDS = "backend_pools";

    @Override
    public void up(Schema schema) {
        schema.table(KEYS, table -> {
            // 默认 0：不设置即普通等级，与既有数据兼容
            table.integer("level").notNull().defaultValue(0);
        });
        schema.table(BACKENDS, table -> {
            // 默认 0：不设置即对所有等级开放
            table.integer("min_level").notNull().defaultValue(0);
        });
    }

    @Override
    public void down(Schema schema) {
        schema.table(KEYS, table -> table.dropColumn("level"));
        schema.table(BACKENDS, table -> table.dropColumn("min_level"));
    }
}
