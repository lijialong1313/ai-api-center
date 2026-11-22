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
 * 管理员使用<b>统一身份认证</b>登录，本系统不保存口令；密码校验环节并不存在，
 * 因此 {@code auth} 模块「密码校验与应用层解耦」的设计在这里天然成立。
 * <p>
 * 守卫 {@code admin} 使用 {@code jwt} 驱动（引入 {@code jwt} 模块后自动可用）：
 * 认证系统回调成功后 {@code Auth.login(admin)} 签发 token，后续 {@code /admin/**}
 * 由 {@code Authenticate("admin")} 中间件校验 Bearer token。
 * <p>
 * 提供者 {@code admins} 绑定 {@link AdminUser}，{@code retrieveById} 每次从库里取最新记录，
 * 所以后台禁用账号能立即生效，不会被请求级缓存挡住。
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
