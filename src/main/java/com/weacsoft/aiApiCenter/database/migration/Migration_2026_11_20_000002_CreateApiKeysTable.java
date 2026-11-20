package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 建 API Key 表 {@code api_keys}（文档 §5.2）。
 * <p>
 * 权重用于平滑加权轮询；{@code key_hash} 存 Key 的 SHA-256，
 * 库中不留明文（明文只在创建时返回一次）。
 */
@MigrationAnnotation
public class Migration_2026_11_20_000002_CreateApiKeysTable implements Migration {

    static final String TABLE = "api_keys";

    @Override
    public void up(Schema schema) {
        schema.create(TABLE, table -> {
            table.id();
            table.string("key_hash", 128).unique();
            table.string("key_prefix", 16).notNull();
            table.string("label", 128).nullable();
            table.integer("weight").notNull().defaultValue(1);
            table.booleanColumn("enabled").notNull().defaultValue(true);
            table.timestamp("last_used_at").nullable();
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
