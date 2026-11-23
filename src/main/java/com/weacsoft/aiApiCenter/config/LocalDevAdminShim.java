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
 * 放行同时向请求属性写入一个未持久化的 {@link AdminUser}：控制器普遍通过
 * {@code Auth.guard("admin").user()} 取当前登录者用于页面展示，认证关闭后该调用
 * 返回 {@code null}，模板取值会抛空指针。本类写入的虚拟账号供其读取。
 * <ul>
 *   <li>不写库，不产生真实账号；</li>
 *   <li>不是登录态，{@code Auth.check()} 仍为 false；</li>
 *   <li>{@code auth-enabled} 为 true 时本类不参与。</li>
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
        // id 取负值：便于识别其非真实账号，同时避免被当作有效主键查询数据库
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
