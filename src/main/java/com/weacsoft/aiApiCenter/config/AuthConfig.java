package com.weacsoft.aiApiCenter.config;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.jaravel.vendor.auth.RegisterGuard;
import com.weacsoft.jaravel.vendor.auth.RegisterProvider;
import com.weacsoft.jaravel.vendor.auth.contract.GuardDefinition;
import com.weacsoft.jaravel.vendor.auth.contract.UserProvider;
import com.weacsoft.jaravel.vendor.database.EloquentUserProvider;
import org.springframework.context.annotation.Configuration;

/**
 * 认证配置，对齐 Laravel {@code config/auth.php}。
 * <p>
 * 管理员使用统一身份认证登录，本系统不保存口令。
 * <p>
 * 守卫 {@code admin} 使用 {@code jwt} 驱动：认证系统回调成功后 {@code Auth.login(admin)}
 * 签发 token，后续 {@code /admin/**} 由认证中间件校验。
 * <p>
 * 提供者 {@code admins} 绑定 {@link AdminUser}，{@code retrieveById} 每次从库里取最新记录，
 * 因此后台禁用账号立即生效。
 */
@Configuration
public class AuthConfig {

    /**
     * 管理员用户提供者，按主键（{@code id}）取出 {@link AdminUser}。
     * <p>
     * 使用注解注册而非 {@code @Bean}，避免与容器中同名 bean 冲突（框架统一约定）。
     */
    @RegisterProvider("admins")
    public UserProvider adminsProvider(AdminUser adminUserModel) {
        return new EloquentUserProvider<>(adminUserModel, "staff_no");
    }

    /**
     * 管理后台守卫：{@code jwt} 驱动 + {@code admins} 提供者，并设为默认守卫。
     * <p>
     * 设为默认后，{@code Auth.check()} / {@code Auth.user()} / {@code Auth.token()}
     * 都直接作用于本守卫，页面与控制器无需显式指定守卫名。
     */
    @RegisterGuard(value = "admin", defaultGuard = true)
    public GuardDefinition adminGuard() {
        return GuardDefinition.of("jwt", "admins");
    }
}
