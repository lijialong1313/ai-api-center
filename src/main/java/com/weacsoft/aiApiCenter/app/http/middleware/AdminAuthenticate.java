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
 * 与框架自带的 {@code Authenticate} 的差别只有一点，但很关键：
 * <b>浏览器访问后台时 JWT 在 Cookie 里，而不是 {@code Authorization: Bearer}</b>。
 * 框架的 {@code JwtGuard} 只认 Authorization 头，所以这里在进入守卫前把 Cookie
 * 里的 token 补写成 Authorization 头，让 JWT 守卫能正常解析——一处适配，
 * 页面与 API 共用同一套令牌校验逻辑。
 * <p>
 * <b>必须写到 {@link AuthContext#get()} 的那个请求上</b>，而不是中间件拿到的
 * {@code request} 参数：框架路由层会先 {@code buildFromServerRequest} 出一个 Request
 * 放进 {@code AuthContext}，之后才把<b>另外</b>一个 Request 实例交给中间件链。
 * 二者并非同一实例（{@code AuthContext.get() == request} 为 false），
 * 改错对象的话守卫读到的仍是原始请求，Cookie 里的 token 永远补不上——
 * 表现为「带 Authorization 头能过、只带 Cookie 一定 302」。
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
