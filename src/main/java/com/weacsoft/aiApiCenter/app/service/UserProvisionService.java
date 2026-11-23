package com.weacsoft.aiApiCenter.app.service;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import com.weacsoft.aiApiCenter.config.SsoProperties;
import com.weacsoft.jaravel.vendor.core.SpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一身份认证用户 → 本地管理员账号的开通与同步。
 * <p>
 * 首次登录时创建 {@code admin_users} 记录，之后每次登录刷新姓名 / 部门 / 角色
 * （组织信息以认证系统为准，本地缓存需随之更新）。
 * <p>
 * 管理员判定：{@code allRole} 命中 {@code app.sso.admin-roles} 中任一角色即为管理员。
 * 认证通过不等于授予管理权限。
 */
@Service
public class UserProvisionService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisionService.class);

    private final SsoProperties ssoProperties;

    public UserProvisionService(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    /**
     * 按统一身份认证返回的身份开通 / 更新本地账号。
     *
     * @param ssoUser 认证系统返回的用户身份
     * @return 本地账号（已持久化）
     */
    public AdminUser provision(SsoService.SsoUser ssoUser) {
        AdminUser existing = AdminUser.findByStaffNo(ssoUser.staffNo());
        boolean isAdmin = matchesAdminRole(ssoUser);

        AdminUser user = existing != null ? existing : new AdminUser();
        user.setStaffNo(ssoUser.staffNo());
        user.setName(ssoUser.name());
        user.setCollege(ssoUser.college());
        user.setRoleName(ssoUser.roleName());
        user.setAllRoles(ssoUser.allRoles() == null ? null : String.join(",", ssoUser.allRoles()));
        user.setLastLoginAt(TimestampFill.nowString());
        if (existing == null) {
            user.setRole(isAdmin ? "admin" : "user");
            user.setEnabled(true);
            user.setCreatedAt(TimestampFill.nowString());
        }
        user.setUpdatedAt(TimestampFill.nowString());

        AdminUser saved = user.save();
        log.info("[sso] 账号同步完成: staffNo={}, name={}, 管理员={}, 新建={}",
                ssoUser.staffNo(), ssoUser.name(), saved != null && saved.isAdmin(), existing == null);
        return saved != null ? saved : user;
    }

    /**
     * 是否为管理员：{@code allRole} 命中配置的管理员角色。
     * <p>
     * 已是 {@code admin} 的账号不会因本次角色列表未命中而被降级；降级需在后台人工调整。
     */
    public boolean matchesAdminRole(SsoService.SsoUser ssoUser) {
        List<String> roles = ssoUser.allRoles();
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        String configured = ssoProperties.getAdminRoles();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        for (String allowed : configured.split(",")) {
            String target = allowed.trim();
            if (target.isEmpty()) {
                continue;
            }
            for (String role : roles) {
                if (role != null && role.trim().equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 按主键取本地账号（JWT 守卫的 provider 会走这条路，这里供页面直接使用） */
    public AdminUser findById(Object id) {
        if (id == null) {
            return null;
        }
        return AdminUser.self().find(id).toObject();
    }

    /** 判断当前会话/令牌对应的账号是否仍有管理员权限 */
    public boolean isAdmin(AdminUser user) {
        return user != null && user.isAdmin();
    }

    /** Spring 容器中取本服务（供模板/静态上下文使用） */
    public static UserProvisionService instance() {
        return SpringContext.bean(UserProvisionService.class);
    }
}
