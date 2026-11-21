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
 * <b>为什么需要它</b>：后台页面普遍要显示右上角的「谁登录了」。
 * 认证关闭时（{@code app.admin.auth-enabled=false}）{@code Auth.guard("admin").user()}
 * 返回 {@code null}，模板里 {@code {{ $user->getName() }}} 会直接抛空指针把页面打成 500。
 * <p>
 * 取值顺序：
 * <ol>
 *   <li>请求属性 {@code current_admin} —— 由 {@link LocalDevAdminShim} 在免认证模式下写入；</li>
 *   <li>{@code Auth.guard("admin").user()} —— 正常登录态。</li>
 * </ol>
 * <p>
 * 注意这是<b>展示层</b>用的取法，不能拿它做权限判断：免认证模式下的虚拟管理员
 * 并不代表真实登录。权限判断仍走 {@code RequireAdmin} 中间件。
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
     * 取当前管理员；没有时返回一个仅用于展示的占位对象。
     * <p>
     * 模板渲染场景用这个：宁可显示「未登录」，也不让页面 500。
     *
     * @param request 当前请求
     * @return 管理员或占位对象，永不返回 {@code null}
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
