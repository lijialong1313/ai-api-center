package com.weacsoft.aiApiCenter.config;

import com.weacsoft.aiApiCenter.app.http.controller.AdminController;
import com.weacsoft.aiApiCenter.app.http.controller.LoginController;
import com.weacsoft.aiApiCenter.app.http.controller.OpenAiGatewayController;
import com.weacsoft.aiApiCenter.app.http.middleware.AdminAuthenticate;
import com.weacsoft.aiApiCenter.app.http.middleware.RequireAdmin;
import com.weacsoft.jaravel.vendor.route.Route;
import com.weacsoft.jaravel.vendor.route.Router;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 路由注册（对齐 Laravel {@code routes/web.php}）。
 * <p>
 * <b>管理后台与 DSH 流量彻底分离</b>（文档 §9.2 第 9 条）：
 * <ul>
 *   <li>DSH 走 {@code /v1/**}（OpenAI 兼容端点，后续接入调度层）；</li>
 *   <li>管理后台走 {@code /admin/**}，挂 {@link AdminAuthenticate} + {@link RequireAdmin} 两道中间件。</li>
 * </ul>
 * 登录相关路由（{@code /admin/login}、{@code /admin/sso/callback}）<b>不挂认证中间件</b>，
 * 否则会陷入「要先登录才能登录」的死循环。
 */
@Configuration
public class RouteConfig {

    private final LoginController loginController;
    private final AdminController adminController;
    private final OpenAiGatewayController openAiGatewayController;
    private final AdminAccessProperties adminAccess;

    public RouteConfig(LoginController loginController,
                       AdminController adminController,
                       OpenAiGatewayController openAiGatewayController,
                       AdminAccessProperties adminAccess) {
        this.loginController = loginController;
        this.adminController = adminController;
        this.openAiGatewayController = openAiGatewayController;
        this.adminAccess = adminAccess;
    }

    @Bean
    public Router router() {
        Router router = new Router();

        // 全局中间件：裁剪空白 + 空串转 null（对齐 Laravel Kernel $middleware）
        router.middleware("trim");
        router.middleware("nullEmpty");

        // ---------- 健康探针（供运维 / 负载均衡用） ----------
        router.get("/healthz", request -> com.weacsoft.jaravel.vendor.http.controller.response.ResponseBuilder
                .json(Map.of("status", "ok")));

        // ---------- DSH 流量：OpenAI 兼容端点（文档 §9.2 第 9 条要求与后台分离） ----------
        // 走 jaravel 自己的路由与 Response 契约：流式（SSE）与 JSON 错误共用同一个
        // Response 类型，因此成功与失败可以共存于同一个处理方法。
        //
        // 注意：逐个列出端点而不是 "/**"。jaravel 的 URI 规范化会在路由末尾补斜杠，
        // "/v1/**" 会变成 "/v1/**/"，而 Spring 的 PathPattern 要求 "**" 必须在末尾，
        // 启动时会报 Invalid mapping pattern。
        router.group(Map.of(Route.Group.PREFIX, "v1"), v1 -> {
            v1.post("/chat/completions", openAiGatewayController::proxy);   // DSH 主用
            v1.post("/completions", openAiGatewayController::proxy);
            v1.post("/embeddings", openAiGatewayController::proxy);
            v1.get("/models", openAiGatewayController::proxy);
        });
        // ---------- 管理后台：登录（免认证） ----------
        router.group(Map.of(Route.Group.PREFIX, "admin"), admin -> {
            // 登录页 / 发起统一身份认证
            admin.get("/login", loginController::login);
            // 统一身份认证回调（认证系统带 code 跳回这里）
            admin.get("/sso/callback", loginController::callback);
            // 登出
            admin.get("/logout", loginController::logout);

            // ---------- 管理后台：需要登录 + 管理员权限 ----------
            //
            // app.admin.auth-enabled=false 时整段跳过认证，方便在没有统一身份认证凭据的
            // 阶段本地试用。此时直接用虚拟管理员访问，页面不会因为「没有当前用户」而崩。
            // 安全提示：关闭后任何人都能改 Key 与后端地址，只能在本机联调用。
            admin.group(Map.of(), protectedAdmin -> {
                if (adminAccess.isAuthEnabled()) {
                    protectedAdmin.middleware(new AdminAuthenticate());
                    protectedAdmin.middleware(new RequireAdmin());
                } else {
                    protectedAdmin.middleware(new LocalDevAdminShim(adminAccess));
                }

                // 概览
                protectedAdmin.get("", adminController::index);
                protectedAdmin.get("/", adminController::index);
                protectedAdmin.get("/forbidden", adminController::forbidden);

                // 当前登录者（JSON）
                protectedAdmin.get("/me", loginController::me);

                // API Key 管理
                protectedAdmin.get("/api-keys", adminController::apiKeys);
                protectedAdmin.post("/api-keys", adminController::createApiKey);
                protectedAdmin.post("/api-keys/update", adminController::updateApiKey);
                protectedAdmin.post("/api-keys/toggle", adminController::toggleApiKey);
                protectedAdmin.post("/api-keys/delete", adminController::deleteApiKey);

                // 管理员管理
                protectedAdmin.get("/users", adminController::adminUsers);
                protectedAdmin.post("/users", adminController::createAdminUser);
                protectedAdmin.post("/users/update", adminController::updateAdminUser);
                protectedAdmin.post("/users/toggle-role", adminController::toggleAdminUserRole);
                protectedAdmin.post("/users/toggle-enabled", adminController::toggleAdminUserEnabled);
                protectedAdmin.post("/users/delete", adminController::deleteAdminUser);

                // GPU 后端管理
                protectedAdmin.get("/backends", adminController::backends);
                protectedAdmin.post("/backends", adminController::createBackend);
                protectedAdmin.post("/backends/update", adminController::updateBackend);
                protectedAdmin.post("/backends/toggle", adminController::toggleBackend);
                protectedAdmin.post("/backends/delete", adminController::deleteBackend);
                protectedAdmin.post("/backends/health-check", adminController::healthCheckBackend);
            });
        });

        return router;
    }
}
