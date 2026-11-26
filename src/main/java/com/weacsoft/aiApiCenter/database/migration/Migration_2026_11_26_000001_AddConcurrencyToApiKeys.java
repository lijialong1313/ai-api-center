package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 为 API Key 增加「会话并发上限」字段，把同会话严格串行放宽为可配置的并行度。
 * <p>
 * 背景：DSH 客户端在同一会话内会并行发起多个 LLM 请求（主请求 + 自动起标题），
 * 而旧版准入层对同一 {@code x-session-id} 只允许一个在途请求，后来的请求被 503
 * 拒绝并引导重试，用户表现为「首次请求要失败两三次才成功」。
 * <p>
 * {@code api_keys.concurrency} —— 该 Key 同一会话允许的最大在途请求数，默认 1
 * （保持旧版串行语义，既有数据行为不变）；设为 N 后，同一会话最多 N 个请求并行
 * 转发到 vLLM，超出部分才返回 503 + Retry-After。
 */
@MigrationAnnotation
public class Migration_2026_11_26_000001_AddConcurrencyToApiKeys implements Migration {

    static final String KEYS = "api_keys";

    @Override
    public void up(Schema schema) {
        schema.table(KEYS, table -> {
            // 默认 1：不设置即严格串行，与既有数据兼容
            table.integer("concurrency").notNull().defaultValue(1);
        });
    }

    @Override
    public void down(Schema schema) {
        schema.table(KEYS, table -> table.dropColumn("concurrency"));
    }
}
