package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 给 {@code api_keys} 增加可回显的明文 Key。
 * <p>
 * <b>为什么改设计</b>：最初只存 SHA-256，明文仅创建时显示一次。但实际运维中管理后台
 * 需要「随时复制 Key 去配置客户端」——只显示一次意味着丢掉就只能重建，
 * 每建一次都要改一遍所有客户端配置。因此改为同时存明文。
 * <p>
 * <b>安全上的取舍（必须知情）</b>：
 * <ul>
 *   <li>库里存明文，数据库一旦泄露，所有 Key 直接可用；</li>
 *   <li>相比之下只存哈希时，泄露的哈希无法反推出 Key。</li>
 * </ul>
 * 缓解措施：界面上默认打码，点「显示」才展示；日志里绝不打印明文。
 * 如果将来要收紧，可以改成「存加密值 + 密钥走环境变量」，
 * 这样数据库泄露仍需拿到进程环境变量才能解密。
 * <p>
 * {@code key_hash} 仍然保留并继续作为唯一索引：鉴权时按哈希查询，
 * 不依赖明文列，查询路径不变。
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
