package com.weacsoft.aiApiCenter.app.http.controller;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.app.service.SsoService;
import com.weacsoft.aiApiCenter.app.service.UserProvisionService;
import com.weacsoft.aiApiCenter.config.SsoProperties;
import com.weacsoft.jaravel.vendor.auth.facade.Auth;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import com.weacsoft.jaravel.vendor.http.controller.response.Response;
import com.weacsoft.jaravel.vendor.http.controller.response.ResponseBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 管理员登录：走<b>统一身份认证</b>（OAuth2 授权码），本系统不接触用户口令。
 * <p>
 * 流程：
 * <ol>
 *   <li>{@code GET /admin/login} → 未登录时 302 到认证系统授权页；</li>
 *   <li>用户在认证系统完成登录（拖动验证码等由认证方处理）；</li>
 *   <li>{@code GET /admin/sso/callback?code=...} → 服务端换 token、取用户信息；</li>
 *   <li>按学工号开通/更新本地 {@link AdminUser}，{@code Auth.login(user)} 签发 JWT；</li>
 *   <li>把 JWT 写入 Cookie（浏览器访问后台用）后 302 到管理首页。</li>
 * </ol>
 * <p>
 * 同时提供 {@code POST /admin/login} 的 JSON 入口给纯 API 调用方：它<b>不接受口令</b>，
 * 而是接受已拿到的 JWT 做校验；真正的登录仍然只能通过统一身份认证。
 * <p>
 * <b>管理员判定</b>：{@code allRole} 命中 {@code app.sso.admin-roles} 才授予 {@code admin}。
 * 非管理员也能登录，但访问后台会被 {@code RequireAdmin} 中间件拦下（403 页面）。
 */
@Controller
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    /** JWT 在浏览器侧的 Cookie 名 */
    public static final String TOKEN_COOKIE = "ac_admin_token";

    private final SsoService ssoService;
    private final SsoProperties ssoProperties;
    private final UserProvisionService provisionService;

    public LoginController(SsoService ssoService, SsoProperties ssoProperties,
                           UserProvisionService provisionService) {
        this.ssoService = ssoService;
        this.ssoProperties = ssoProperties;
        this.provisionService = provisionService;
    }

    /**
     * 登录入口：已登录则直接进后台，否则跳到统一身份认证。
     */
    public Response login(Request request) {
        if (Auth.check()) {
            return ResponseBuilder.redirect("/admin");
        }
        if (!ssoProperties.isConfigured()) {
            // 未配置 client_id/secret 时不能跳认证，给出明确提示而不是白屏
            return ResponseBuilder.view("admin.login", ResponseBuilder.map(
                    "title", "管理员登录",
                    "configured", false,
                    "error", "统一身份认证尚未配置（app.sso.client-id / client-secret），无法登录。"
            ));
        }

        String redirectUri = resolveRedirectUri(request);
        // 两端（授权 / 换令牌）必须用同一个 redirect_uri，先存进会话
        request.putSession(SsoService.SESSION_REDIRECT_URI, redirectUri);

        String authorizeUrl = ssoService.buildAuthorizeUrl(redirectUri);
        log.info("[sso] 跳转统一身份认证: redirectUri={}", redirectUri);
        return ResponseBuilder.redirect(authorizeUrl);
    }

    /**
     * 认证系统回调：{@code code} 换身份 → 开通账号 → 登录 → 回后台。
     */
    public Response callback(Request request) {
        String code = request.query("code");
        if (code == null || code.isBlank()) {
            return failPage("统一身份认证未返回授权码");
        }
        String redirectUri = request.session(SsoService.SESSION_REDIRECT_URI);
        if (redirectUri == null || redirectUri.isBlank()) {
            redirectUri = resolveRedirectUri(request);
        }

        try {
            SsoService.SsoUser ssoUser = ssoService.exchangeCode(code, redirectUri);
            AdminUser user = provisionService.provision(ssoUser);

            if (!user.isActive()) {
                return failPage("账号已被停用，请联系管理员");
            }

            // 登录：签发 JWT（守卫 admin 使用 jwt 驱动）
            Auth.login(user, "admin");
            String token = Auth.token("admin");

            String target = (user.isAdmin() || provisionService.matchesAdminRole(ssoUser))
                    ? "/admin" : "/admin/forbidden";

            log.info("[sso] 登录成功: staffNo={}, name={}, 管理员={}",
                    user.getStaffNo(), user.getName(), user.isAdmin());

            Response response = ResponseBuilder.redirect(target);
            if (token != null && !token.isBlank()) {
                response.addCookie(buildTokenCookie(token));
            }
            return response;
        } catch (SsoService.SsoException e) {
            log.warn("[sso] 回调失败: {}", e.getMessage());
            return failPage(e.getMessage());
        } catch (Exception e) {
            log.error("[sso] 回调异常", e);
            return failPage("登录失败：" + e.getMessage());
        }
    }

    /**
     * 登出：把当前 token 加入黑名单（{@code jaravel.jwt.blacklist-enabled=true}），清除 Cookie。
     */
    public Response logout(Request request) {
        try {
            Auth.logout("admin");
        } catch (Exception e) {
            log.debug("[sso] 登出时清理令牌失败（忽略）: {}", e.getMessage());
        }
        Response response = ResponseBuilder.redirect("/admin/login");
        response.addCookie(clearTokenCookie());
        return response;
    }

    /**
     * 当前登录者信息（JSON）。供后台页面或运维排查使用。
     */
    public Response me(Request request) {
        AdminUser user = Auth.user();
        if (user == null) {
            return ResponseBuilder.error(401, "未登录");
        }
        return ResponseBuilder.json(ResponseBuilder.map(
                "id", user.getId(),
                "staffNo", user.getStaffNo(),
                "name", user.getName(),
                "college", user.getCollege(),
                "roles", user.roleList(),
                "admin", user.isAdmin()
        ));
    }

    /**
     * 解析本次登录要用的 {@code redirect_uri}。
     * <p>
     * 优先级：显式配置 → 按当前请求推导（支持部署在虚拟目录 / 反向代理后）。
     * 推导值时保留 scheme/host/port，只替换路径为回调地址。
     */
    private String resolveRedirectUri(Request request) {
        String configured = ssoProperties.getRedirectUri();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        HttpServletRequest servletRequest = request.getRequest();
        if (servletRequest == null) {
            return "/admin/sso/callback";
        }
        String scheme = servletRequest.getScheme();
        String host = servletRequest.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = servletRequest.getServerName()
                    + (servletRequest.getServerPort() > 0 ? ":" + servletRequest.getServerPort() : "");
        }
        String contextPath = servletRequest.getContextPath() == null ? "" : servletRequest.getContextPath();
        return scheme + "://" + host + contextPath + "/admin/sso/callback";
    }

    /** 登录失败页面（把原因回显给用户，而不是抛 500） */
    private Response failPage(String message) {
        return ResponseBuilder.view("admin.login", ResponseBuilder.map(
                "title", "管理员登录",
                "configured", ssoProperties.isConfigured(),
                "error", message == null ? "登录失败，请重试" : message
        ));
    }

    /** 构造 JWT Cookie（HttpOnly + SameSite=Lax，避免被脚本读取） */
    private jakarta.servlet.http.Cookie buildTokenCookie(String token) {
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(TOKEN_COOKIE, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge((int) (24 * 3600));
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    /** 清除 JWT Cookie */
    private jakarta.servlet.http.Cookie clearTokenCookie() {
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(TOKEN_COOKIE, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        return cookie;
    }

    /** URL 编码（回调失败原因放进 query 时用） */
    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
