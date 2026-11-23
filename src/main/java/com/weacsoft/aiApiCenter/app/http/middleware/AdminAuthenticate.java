package com.weacsoft.aiApiCenter.app.http.middleware;

import com.weacsoft.jaravel.vendor.auth.AuthContext;
import com.weacsoft.jaravel.vendor.auth.facade.Auth;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import com.weacsoft.jaravel.vendor.http.controller.response.Response;
import com.weacsoft.jaravel.vendor.http.controller.response.ResponseBuilder;
import com.weacsoft.jaravel.vendor.http.middleware.Middleware;

/**
 * 管理后台认证中间件：{@code /admin/**} 统一挂载。
 * <p>
 * 与框架自带的 {@code Authenticate} 的差别：浏览器访问后台时 JWT 在 Cookie 里，
 * 而框架的 {@code JwtGuard} 只读 {@code Authorization} 头。这里在进入守卫前把
 * Cookie 中的 token 补写成 Authorization 头，页面与 API 共用同一套令牌校验。
 * <p>
 * 补写必须同时作用于 {@link com.weacsoft.jaravel.vendor.auth.AuthContext#get()} 持有的
 * 请求对象：框架路由层先构造一个 Request 放进 {@code AuthContext}，之后交给中间件链的是
 * 另一个实例，两者不是同一对象。只改中间件参数那个对象，守卫读到的仍是原始请求。
 * <p>
 * 未登录时：页面请求 302 到 {@code /admin/login}；JSON 请求返回 401。
 */
public class AdminAuthenticate implements Middleware {

    /** 与 {@code LoginController.TOKEN_COOKIE} 保持一致 */
    public static final String TOKEN_COOKIE = "ac_admin_token";

    @Override
    public Response handle(Request request, NextFunction next, String... params) {
        String authorization = request.header("Authorization");
        if (authorization == null || authorization.isBlank()) {
            String cookieToken = request.cookie(TOKEN_COOKIE);
            if (cookieToken != null && !cookieToken.isBlank()) {
                String bearer = "Bearer " + cookieToken;
                // 1) 中间件链上的请求（后续中间件与控制器读这个）
                request.replaceHeader("Authorization", bearer);
                // 2) AuthContext 里的请求（JwtGuard 实际读这个，两者不是同一实例）
                Request authRequest = AuthContext.get();
                if (authRequest != null) {
                    authRequest.replaceHeader("Authorization", bearer);
                }
            }
        }

        if (Auth.guard("admin").check()) {
            return next.apply(request);
        }

        if (wantsJson(request)) {
            return ResponseBuilder.error(401, "未登录或登录已过期");
        }
        return ResponseBuilder.redirect("/admin/login");
    }

    /** 是否期望 JSON 响应（后台的异步表单提交） */
    private boolean wantsJson(Request request) {
        String accept = request.header("Accept");
        String requestedWith = request.header("X-Requested-With");
        return (accept != null && accept.contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(requestedWith);
    }
}
