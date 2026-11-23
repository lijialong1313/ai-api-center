package com.weacsoft.aiApiCenter.app.http.controller;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.config.AdminAccessProperties;
import com.weacsoft.aiApiCenter.config.LocalDevAdminShim;
import com.weacsoft.jaravel.vendor.auth.facade.Auth;
import com.weacsoft.jaravel.vendor.http.controller.request.Request;
import org.springframework.stereotype.Component;

/**
 * 「当前展示用管理员」的统一取用入口。
 * <p>
 * 后台页面需要显示当前登录者。认证关闭时 {@code Auth.guard("admin").user()}
 * 返回 {@code null}，模板取值会抛空指针。
 * <p>
 * 取值顺序：
 * <ol>
 *   <li>请求属性 {@code current_admin} —— 由 {@link LocalDevAdminShim} 在免认证模式下写入；</li>
 *   <li>{@code Auth.guard("admin").user()} —— 正常登录态。</li>
 * </ol>
 * <p>
 * 本类仅供展示层使用，不能用于权限判断：免认证模式下的虚拟管理员不代表真实登录态。
 * 权限判断仍由 {@code RequireAdmin} 中间件负责。
 */
@Component
public class CurrentAdmin {

    private final AdminAccessProperties properties;

    public CurrentAdmin(AdminAccessProperties properties) {
        this.properties = properties;
    }

    /**
     * 取当前管理员。
     *
     * @param request 当前请求，可为 {@code null}
     * @return 管理员；都没有时返回 {@code null}
     */
    public AdminUser of(Request request) {
        if (request != null) {
            Object injected = request.getAttribute(LocalDevAdminShim.ATTRIBUTE_CURRENT_ADMIN);
            if (injected instanceof AdminUser admin) {
                return admin;
            }
        }
        return Auth.guard("admin").user();
    }

    /**
     * 取当前管理员；没有时返回一个仅用于展示的占位对象，永不返回 {@code null}。
     *
     * @param request 当前请求
     * @return 管理员或占位对象
     */
    public AdminUser ofOrAnonymous(Request request) {
        AdminUser admin = of(request);
        if (admin != null) {
            return admin;
        }
        AdminUser anonymous = new AdminUser();
        anonymous.setId(-2L);
        anonymous.setStaffNo("-");
        anonymous.setName(properties.isAuthEnabled() ? "未登录" : properties.getMockAdminName());
        anonymous.setRole("user");
        anonymous.setAllRoles("");
        anonymous.setEnabled(false);
        return anonymous;
    }
}
