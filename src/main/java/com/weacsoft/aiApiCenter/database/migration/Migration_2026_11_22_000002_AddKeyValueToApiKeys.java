package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 为 {@code api_keys} 增加 {@code key_value} 列，存放 Key 明文，供管理后台复制。
 * <p>
 * <b>安全影响</b>：明文存储意味着数据库泄露即可直接使用全部 Key；仅存哈希时
 * 无法由哈希反推 Key。
 * <p>
 * 缓解措施：界面默认显示掩码，需手动展开；日志不输出该字段。
 * <p>
 * {@code key_hash} 保留并继续作为唯一索引：鉴权按哈希查询，不依赖本列，
 * 查询路径不变。
 */
@MigrationAnnotation
public class Migration_2026_11_22_000002_AddKeyValueToApiKeys implements Migration {

    static final String TABLE = "api_keys";

    @Override
    public void up(Schema schema) {
        schema.table(TABLE, table -> {
            // 明文 Key，供后台复制
            table.string("key_value", 128).nullable();
        });
    }

    @Override
    public void down(Schema schema) {
        schema.table(TABLE, table -> table.dropColumn("key_value"));
    }
}
