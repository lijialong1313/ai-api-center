package com.weacsoft.aiApiCenter.config;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import com.weacsoft.jaravel.vendor.http.controller.response.Response;
import com.weacsoft.jaravel.vendor.http.middleware.Middleware;

/**
 * 关闭认证时的「本地调试管理员」。
 * <p>
 * {@code app.admin.auth-enabled=false} 时替代 {@code AdminAuthenticate} + {@code RequireAdmin}
 * 两道中间件，直接放行。
 * <p>
 * <b>它为什么需要做点事，而不只是一个空中间件</b>：
 * {@code AdminController} 里普遍写着 {@code Auth.guard("admin").user()} 来取当前登录者，
 * 用于页面右上角展示。认证关掉后这个调用返回 {@code null}，模板里
 * {@code {{ $user->getName() }}} 就会抛空指针，后台直接 500。
 * <p>
 * 因此本类把一个<b>未持久化的</b> {@link AdminUser} 放进请求属性
 * （{@code current_admin}），控制器再优先读它。注意：
 * <ul>
 *   <li>它<b>不写库</b>，不产生任何真实账号；</li>
 *   <li>它不是登录态，{@code Auth.check()} 仍为 false；</li>
 *   <li>一旦把 {@code auth-enabled} 改回 true，本类完全不参与。</li>
 * </ul>
 */
public class LocalDevAdminShim implements Middleware {

    /** 请求属性名：控制器通过它取「当前展示用管理员」 */
    public static final String ATTRIBUTE_CURRENT_ADMIN = "current_admin";

    private final AdminAccessProperties properties;

    public LocalDevAdminShim(AdminAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    public Response handle(Request request, NextFunction next, String... params) {
        AdminUser dev = new AdminUser();
        // id 给一个负数：一眼能看出不是真实账号，也避免被当成有效主键去查库
        dev.setId(-1L);
        dev.setStaffNo(properties.getMockAdminStaffNo());
        dev.setName(properties.getMockAdminName());
        dev.setRole("admin");
        dev.setRoleName("admin");
        dev.setAllRoles("admin");
        dev.setEnabled(true);
        request.setAttribute(ATTRIBUTE_CURRENT_ADMIN, dev);
        return next.apply(request);
    }
}
