package com.weacsoft.aiApiCenter.app.http.controller;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import com.weacsoft.aiApiCenter.app.service.BackendPoolService;
import com.weacsoft.aiApiCenter.app.service.SchedulerConfigCache;
import com.weacsoft.jaravel.vendor.auth.facade.Auth;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import com.weacsoft.jaravel.vendor.http.controller.response.Response;
import com.weacsoft.jaravel.vendor.http.controller.response.ResponseBuilder;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

/**
 * 管理后台页面与 REST 接口（文档 §5）：
 * <ul>
 *   <li>{@code /admin} 首页（概览）</li>
 *   <li>API Key 管理：列表 / 新建 / 改权重 / 启用禁用 / 删除</li>
 *   <li>GPU 后端管理：列表 / 新建 / 编辑 / 启用禁用 / 删除 / 健康检查</li>
 * </ul>
 * 认证由 {@code AdminAuthenticate} + {@code RequireAdmin} 两道中间件保证（见 {@code RouteConfig}）。
 */
@Controller
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final BackendPoolService backendPoolService;
    private final SchedulerConfigCache configCache;

    public AdminController(ApiKeyService apiKeyService,
                           BackendPoolService backendPoolService,
                           SchedulerConfigCache configCache) {
        this.apiKeyService = apiKeyService;
        this.backendPoolService = backendPoolService;
        this.configCache = configCache;
    }

    // ==================== 首页 ====================

    /** 后台首页：概览统计。 */
    public Response index(Request request) {
        AdminUser user = Auth.guard("admin").user();
        List<ApiKey> keys = apiKeyService.list();
        List<BackendPool> backends = backendPoolService.list();

        long enabledKeys = keys.stream().filter(ApiKey::isUsable).count();
        long enabledBackends = backends.stream().filter(BackendPool::isUsable).count();
        int totalCapacity = backends.stream()
                .filter(BackendPool::isUsable)
                .mapToInt(b -> b.getCapacity() == null ? 0 : b.getCapacity())
                .sum();

        return ResponseBuilder.view("admin.dashboard", ResponseBuilder.map(
                "title", "控制台",
                "user", user,
                "keyCount", keys.size(),
                "enabledKeyCount", enabledKeys,
                "backendCount", backends.size(),
                "enabledBackendCount", enabledBackends,
                "totalCapacity", totalCapacity,
                "active", "dashboard"
        ));
    }

    // ==================== API Key 管理 ====================

    /** Key 列表页。创建成功后通过 query 参数带回一次明文。 */
    public Response apiKeys(Request request) {
        String newKey = request.query("newKey");
        List<ApiKey> keys = apiKeyService.list();
        return ResponseBuilder.view("admin.api-keys", ResponseBuilder.map(
                "title", "API Key 管理",
                "user", Auth.guard("admin").user(),
                "keys", keys,
                // 集合长度在控制器算好传进去：Blade 里没有 size() 函数
                "keyTotal", keys.size(),
                "newKey", newKey,
                "active", "api-keys"
        ));
    }

    /**
     * 新建 Key（表单提交）。
     * <p>
     * 明文只在这里出现一次：重定向到列表页时用 query 参数带回，页面提示「仅显示一次」。
     */
    public Response createApiKey(Request request) {
        String label = request.input("label");
        Integer weight = parseInt(request.input("weight"), 1);
        ApiKeyService.CreatedKey created = apiKeyService.create(label, weight);
        return ResponseBuilder.redirect("/admin/api-keys?newKey="
                + urlEncode(created.plaintext()));
    }

    /** 修改权重 / 名称 / 启用状态（表单提交后回列表页）。 */
    public Response updateApiKey(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        Integer weight = request.input("weight") == null ? null : parseInt(request.input("weight"), 1);
        String label = request.input("label");
        Boolean enabled = parseBool(request.input("enabled"));
        apiKeyService.update(id, label, weight, enabled);
        return ResponseBuilder.redirect("/admin/api-keys");
    }

    /** 启用 / 禁用（表单 switch 提交）。 */
    public Response toggleApiKey(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        ApiKey key = apiKeyService.get(id);
        if (key == null) {
            return ResponseBuilder.error(404, "Key 不存在");
        }
        apiKeyService.setEnabled(id, !Boolean.TRUE.equals(key.getEnabled()));
        return ResponseBuilder.redirect("/admin/api-keys");
    }

    /** 删除 Key。 */
    public Response deleteApiKey(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        apiKeyService.delete(id);
        return ResponseBuilder.redirect("/admin/api-keys");
    }

    // ==================== GPU 后端管理 ====================

    /** 后端列表页。 */
    public Response backends(Request request) {
        List<BackendPool> backends = backendPoolService.list();
        return ResponseBuilder.view("admin.backends", ResponseBuilder.map(
                "title", "GPU 后端管理",
                "user", Auth.guard("admin").user(),
                "backends", backends,
                // 集合长度在控制器算好传进去：Blade 里没有 size() 函数
                "backendTotal", backends.size(),
                "message", request.query("message"),
                "active", "backends"
        ));
    }

    /** 新建后端。 */
    public Response createBackend(Request request) {
        backendPoolService.create(
                request.input("backendId"),
                request.input("name"),
                request.input("baseUrl"),
                parseInt(request.input("capacity"), 1),
                request.input("healthPath")
        );
        return ResponseBuilder.redirect("/admin/backends?message=" + urlEncode("后端已创建"));
    }

    /** 编辑后端（地址 / 容量 / 健康路径）。 */
    public Response updateBackend(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        backendPoolService.update(
                id,
                request.input("name"),
                request.input("baseUrl"),
                request.input("capacity") == null ? null : parseInt(request.input("capacity"), 1),
                parseBool(request.input("enabled")),
                request.input("healthPath")
        );
        return ResponseBuilder.redirect("/admin/backends?message=" + urlEncode("后端已更新"));
    }

    /** 启用 / 禁用后端。 */
    public Response toggleBackend(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        BackendPool pool = backendPoolService.get(id);
        if (pool == null) {
            return ResponseBuilder.error(404, "后端不存在");
        }
        backendPoolService.update(id, null, null, null,
                !Boolean.TRUE.equals(pool.getEnabled()), null);
        return ResponseBuilder.redirect("/admin/backends?message=" + urlEncode("状态已切换"));
    }

    /** 删除后端。 */
    public Response deleteBackend(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        backendPoolService.delete(id);
        return ResponseBuilder.redirect("/admin/backends?message=" + urlEncode("后端已删除"));
    }

    /** 手动触发健康检查。 */
    public Response healthCheckBackend(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        BackendPoolService.HealthResult result = backendPoolService.healthCheck(id);
        if (result == null) {
            return ResponseBuilder.error(404, "后端不存在");
        }
        String message = (result.healthy() ? "健康：" : "不健康：") + result.detail();
        return ResponseBuilder.redirect("/admin/backends?message=" + urlEncode(message));
    }

    // ==================== 无权限页 ====================

    /** 已登录但非管理员时的提示页（不暴露任何业务数据）。 */
    public Response forbidden(Request request) {
        return ResponseBuilder.view("admin.forbidden", ResponseBuilder.map(
                "title", "无访问权限",
                "user", Auth.guard("admin").user()
        ));
    }

    // ==================== 工具方法 ====================

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String value, Integer fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 解析表单布尔值。
     * <p>
     * HTML checkbox 未勾选时<b>不会提交字段</b>，所以 {@code null} 表示「未勾选」→ false；
     * 但「想改但没传」和「明确想设为 false」无法区分，因此更新接口对 enabled
     * 只在显式传入值时生效（见 {@code ApiKeyService.update} 的 null 语义）。
     */
    private static Boolean parseBool(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
