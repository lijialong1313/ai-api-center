package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 为 API Key 与 GPU 后端增加「等级」字段，支持分级调度。
 * <p>
 * <b>需求场景</b>：部分 GPU（例如算力更强或显存更大的机器）只应服务高等级 Key，
 * 低等级 Key 不得占用；其余 GPU 对全体开放。
 * <p>
 * <b>设计</b>：
 * <ul>
 *   <li>{@code api_keys.level} —— Key 的等级，默认 0；</li>
 *   <li>{@code backend_pools.min_level} —— 该后端要求的最低 Key 等级，默认 0（即不限制）。</li>
 * </ul>
 * 调度时按 {@code key.level >= backend.min_level} 过滤后端。数值越大等级越高。
 * <p>
 * <b>为什么用「最低等级」而不是「允许的等级列表」</b>：等级本身是有序的（0 普通、
 * 1 高优、2 内部），用阈值表达「这个后端至少要多高等级」比维护白名单更直观，
 * 也不会在新增等级时漏配。
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
