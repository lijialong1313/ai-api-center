package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 给 {@code backend_pools} 加上游 API Key 字段。
 * <p>
 * 客户端提交的是中间件颁发的 Key，vLLM 不予识别，转发时必须换成后端自己的 Key。
 * 该字段按后端逐行配置：多个后端可能属于不同环境、使用不同 Key，或有的开放有的需鉴权。
 */
@MigrationAnnotation
public class Migration_2026_11_22_000001_AddUpstreamApiKeyToBackendPools implements Migration {

    static final String TABLE = "backend_pools";

    @Override
    public void up(Schema schema) {
        schema.table(TABLE, table -> {
            // 存明文：转发时必须原样取用（无法用哈希）。这个字段属于敏感信息，
            // 后台界面回显时只显示掩码，不展示完整值。
            table.string("api_key", 512).nullable();
            // 上游是否要求鉴权。留 null 表示「按 api_key 是否为空推断」。
            // 单独一个开关是为了兼容「Key 为空但需要别的头」这类情况以后的扩展。
            table.string("auth_header", 64).nullable();
            // 模型名映射：中间件不强制改模型名，但后台要知道这个后端提供什么模型，
            // 便于健康检查时探测 /v1/models。
            table.string("default_model", 128).nullable();
        });
    }

    @Override
    public void down(Schema schema) {
        schema.table(TABLE, table -> {
            table.dropColumn("api_key");
            table.dropColumn("auth_header");
            table.dropColumn("default_model");
        });
    }
}
