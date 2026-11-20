package com.weacsoft.aiApiCenter.config;

import com.weacsoft.aiApiCenter.app.http.controller.AdminController;
import com.weacsoft.aiApiCenter.app.http.controller.LoginController;
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

    public RouteConfig(LoginController loginController, AdminController adminController) {
        this.loginController = loginController;
        this.adminController = adminController;
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

        // ---------- 管理后台：登录（免认证） ----------
        router.group(Map.of(Route.Group.PREFIX, "admin"), admin -> {
            // 登录页 / 发起统一身份认证
            admin.get("/login", loginController::login);
            // 统一身份认证回调（认证系统带 code 跳回这里）
            admin.get("/sso/callback", loginController::callback);
            // 登出
            admin.get("/logout", loginController::logout);

            // ---------- 管理后台：需要登录 + 管理员权限 ----------
            admin.group(Map.of(), protectedAdmin -> {
                protectedAdmin.middleware(new AdminAuthenticate());
                protectedAdmin.middleware(new RequireAdmin());

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
