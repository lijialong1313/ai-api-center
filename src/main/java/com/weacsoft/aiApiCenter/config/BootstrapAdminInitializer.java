package com.weacsoft.aiApiCenter.config;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.app.service.AdminUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时确保配置的管理员工号存在于 {@code admin_users}。
 * <p>
 * 行为：工号不存在时以 {@code admin} 角色创建；已存在时不修改任何字段，
 * 因此后续在后台把该账号降权或停用之后，重启不会把它改回来。
 * <p>
 * 用途是预先授权：该工号首次通过统一身份认证登录时，
 * {@code UserProvisionService} 按学工号匹配到这条记录，管理员权限即刻生效。
 * <p>
 * 配置项：{@code app.admin.bootstrap-admin-staff-no}，留空表示关闭本机制。
 */
@Configuration
public class BootstrapAdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    @Bean
    public ApplicationRunner bootstrapAdminRunner(AdminAccessProperties properties,
                                                  AdminUserService adminUserService) {
        return args -> {
            String staffNo = properties.getBootstrapAdminStaffNo();
            if (staffNo == null || staffNo.isBlank()) {
                return;
            }
            String trimmed = staffNo.trim();
            AdminUser existing = AdminUser.findByStaffNo(trimmed);
            if (existing != null) {
                log.info("[bootstrap] 管理员工号已存在，不做修改: staffNo={}, role={}",
                        trimmed, existing.getRole());
                return;
            }
            String name = properties.getBootstrapAdminName();
            AdminUser created = adminUserService.create(
                    trimmed,
                    (name == null || name.isBlank()) ? trimmed : name.trim(),
                    AdminUserService.ROLE_ADMIN);
            if (created != null) {
                log.info("[bootstrap] 已创建启动管理员: staffNo={}, role={}",
                        trimmed, created.getRole());
            }
        };
    }
}
