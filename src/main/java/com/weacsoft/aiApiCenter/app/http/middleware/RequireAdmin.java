package com.weacsoft.aiApiCenter.app.http.middleware;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.jaravel.vendor.auth.facade.Auth;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import com.weacsoft.jaravel.vendor.http.controller.response.Response;
import com.weacsoft.jaravel.vendor.http.controller.response.ResponseBuilder;
import com.weacsoft.jaravel.vendor.http.middleware.Middleware;

/**
 * 管理员授权中间件：在 {@link AdminAuthenticate} 之后挂载。
 * <p>
 * 认证不等于授权：能通过统一身份认证的师生很多，只有 {@code role=admin} 且账号启用的
 * 才是后台管理员，非管理员访问后台返回 403。
 * <p>
 * 每次请求都从库里取最新 {@link AdminUser}（框架的 provider 不缓存用户对象），
 * 因此后台调整角色或停用账号后立即失效。
 */
public class RequireAdmin implements Middleware {

    @Override
    public Response handle(Request request, NextFunction next, String... params) {
        AdminUser user = Auth.guard("admin").user();
        if (user == null) {
            return ResponseBuilder.redirect("/admin/login");
        }
        if (!user.isActive()) {
            return ResponseBuilder.forbidden("账号已停用，请联系管理员");
        }
        if (!user.isAdmin()) {
            return ResponseBuilder.forbidden("当前账号没有管理后台权限（需要教师/管理员角色）");
        }
        return next.apply(request);
    }
}
