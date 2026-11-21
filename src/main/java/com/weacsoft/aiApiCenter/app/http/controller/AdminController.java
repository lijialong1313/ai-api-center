package com.weacsoft.aiApiCenter.app.http.controller;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import com.weacsoft.aiApiCenter.app.service.BackendPoolService;
import com.weacsoft.aiApiCenter.app.service.AdminUserService;
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
    private final CurrentAdmin currentAdmin;
    private final AdminUserService adminUserService;

    public AdminController(ApiKeyService apiKeyService,
                           BackendPoolService backendPoolService,
                           SchedulerConfigCache configCache,
                           CurrentAdmin currentAdmin,
                           AdminUserService adminUserService) {
        this.apiKeyService = apiKeyService;
        this.backendPoolService = backendPoolService;
        this.configCache = configCache;
        this.currentAdmin = currentAdmin;
        this.adminUserService = adminUserService;
    }

    // ==================== 首页 ====================

    /** 后台首页：概览统计。 */
    public Response index(Request request) {
        AdminUser user = currentAdmin.ofOrAnonymous(request);
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
                "adminCount", adminUserService.countAdmins(),
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
                "user", currentAdmin.ofOrAnonymous(request),
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

    /**
     * 重新生成 Key 明文（保留名称、权重、启用状态与 id）。
     * <p>
     * 用于 Key 泄露或丢失：原地换值，客户端只需更新一个字符串，
     * 不用重建记录、不用重填名称。
     */
    public Response regenerateApiKey(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        String plaintext = apiKeyService.regenerate(id);
        if (plaintext == null) {
            return ResponseBuilder.redirect("/admin/api-keys?message="
                    + urlEncode("Key 不存在"));
        }
        return ResponseBuilder.redirect("/admin/api-keys?newKey=" + urlEncode(plaintext));
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
                "user", currentAdmin.ofOrAnonymous(request),
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
                request.input("healthPath"),
                request.input("apiKey"),
                request.input("defaultModel")
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
                request.input("healthPath"),
                // 空串表示「清空」，null 表示「不改」。表单未提交该字段时 input() 返回 null，
                // 所以「留空保持不变」这个交互靠它成立。
                request.input("apiKey"),
                request.input("defaultModel")
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

    // ==================== 管理员管理 ====================

    /**
     * 管理员列表页。
     * <p>
     * 展示所有账号（含未登录过的「预先授权」记录），并标出当前登录者是谁，
     * 避免误操作把自己停用。
     */
    public Response adminUsers(Request request) {
        AdminUser me = currentAdmin.ofOrAnonymous(request);
        List<AdminUser> users = adminUserService.list();
        return ResponseBuilder.view("admin.users", ResponseBuilder.map(
                "title", "管理员管理",
                "user", me,
                "users", users,
                "userTotal", users.size(),
                "adminTotal", adminUserService.countAdmins(),
                "message", request.query("message"),
                "active", "users"
        ));
    }

    /**
     * 新建管理员账号。
     * <p>
     * 学工号是唯一键：已存在时不会覆盖，而是回列表页提示。
     */
    public Response createAdminUser(Request request) {
        String staffNo = request.input("staffNo");
        String name = request.input("name");
        String role = request.input("role");
        AdminUser created = adminUserService.create(staffNo, name, role);
        if (created == null) {
            return ResponseBuilder.redirect("/admin/users?message="
                    + urlEncode("创建失败：学工号为空或已存在（" + staffNo + "）"));
        }
        return ResponseBuilder.redirect("/admin/users?message="
                + urlEncode("已创建：" + created.getStaffNo()));
    }

    /** 修改角色 / 启用状态。 */
    public Response updateAdminUser(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        Boolean enabled = parseBool(request.input("enabled"));
        String role = request.input("role");
        adminUserService.update(id, request.input("name"), role, enabled);
        return ResponseBuilder.redirect("/admin/users?message=" + urlEncode("已更新"));
    }

    /** 在 admin / user 之间切换。 */
    public Response toggleAdminUserRole(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        AdminUser updated = adminUserService.toggleRole(id);
        String msg = updated == null ? "账号不存在" : ("角色已切换为 " + updated.getRole());
        return ResponseBuilder.redirect("/admin/users?message=" + urlEncode(msg));
    }

    /** 启用 / 停用账号。 */
    public Response toggleAdminUserEnabled(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        AdminUser user = adminUserService.get(id);
        if (user == null) {
            return ResponseBuilder.error(404, "账号不存在");
        }
        adminUserService.setEnabled(id, !Boolean.TRUE.equals(user.getEnabled()));
        return ResponseBuilder.redirect("/admin/users?message=" + urlEncode("状态已切换"));
    }

    /**
     * 删除账号。
     * <p>
     * 最后一个可用管理员不允许删除（否则认证开启后再也没人能进后台）。
     */
    public Response deleteAdminUser(Request request) {
        Long id = parseLong(request.input("id"));
        if (id == null) {
            return ResponseBuilder.error(400, "缺少 id");
        }
        boolean ok = adminUserService.delete(id);
        String msg = ok ? "已删除" : "删除被拒绝：不能删除最后一个可用管理员";
        return ResponseBuilder.redirect("/admin/users?message=" + urlEncode(msg));
    }
    // ==================== 无权限页 ====================

    /** 已登录但非管理员时的提示页（不暴露任何业务数据）。 */
    public Response forbidden(Request request) {
        return ResponseBuilder.view("admin.forbidden", ResponseBuilder.map(
                "title", "无访问权限",
                "user", currentAdmin.ofOrAnonymous(request)
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
