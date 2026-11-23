package com.weacsoft.aiApiCenter.app.service;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理员账号管理。
 * <p>
 * 管理员账号通常在首次统一身份认证登录时自动开通（见 {@link UserProvisionService}）。
 * 本服务用于预先授权与人工调整：
 * <ul>
 *   <li>某人尚未登录过，先按学工号建立管理员记录，其首次登录即生效；</li>
 *   <li>某人已登录但角色未命中 {@code app.sso.admin-roles}，需要单独提权；</li>
 *   <li>离职或转岗时立即停用。</li>
 * </ul>
 * 提权只能通过本服务把 {@code role} 置为 {@code admin} 完成。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /** 角色：管理员 */
    public static final String ROLE_ADMIN = "admin";

    /** 角色：普通用户（可登录，但进不了后台） */
    public static final String ROLE_USER = "user";

    /** 全部账号（管理界面列表用） */
    public List<AdminUser> list() {
        return AdminUser.all();
    }

    /** 按主键取 */
    public AdminUser get(Long id) {
        return AdminUser.findById(id);
    }

    /**
     * 新建管理员账号（预先授权）。
     * <p>
     * 只需要学工号：其余信息（姓名、部门、角色）会在该用户第一次 SSO 登录时
     * 由 {@link UserProvisionService} 自动补齐并覆盖。
     *
     * @param staffNo 学工号（唯一）
     * @param name    姓名，可空
     * @param role    {@code admin} / {@code user}，非法值按 user 处理
     * @return 新建的账号；学工号已存在时返回 null
     */
    public AdminUser create(String staffNo, String name, String role) {
        if (staffNo == null || staffNo.isBlank()) {
            return null;
        }
        String normalized = staffNo.trim();
        if (AdminUser.findByStaffNo(normalized) != null) {
            log.warn("[admin-user] 学工号已存在，拒绝重复创建: {}", normalized);
            return null;
        }

        AdminUser user = new AdminUser();
        user.setStaffNo(normalized);
        user.setName(name == null || name.isBlank() ? normalized : name.trim());
        user.setRole(normalizeRole(role));
        user.setRoleName(user.getRole());
        user.setAllRoles(user.getRole());
        user.setEnabled(true);
        user.setCreatedAt(TimestampFill.nowString());
        user.setUpdatedAt(TimestampFill.nowString());
        AdminUser saved = user.save();
        log.info("[admin-user] 新建账号: staffNo={}, role={}", normalized, user.getRole());
        return saved;
    }

    /**
     * 修改账号的角色与启用状态。
     * <p>
     * 只更新传入的非空字段。角色切换会同步 {@code roleName / allRoles}，
     * 保持界面显示与权限一致。
     *
     * @param id      主键
     * @param role    新角色，null 表示不改
     * @param enabled 新启用状态，null 表示不改
     * @return 更新后的账号；不存在返回 null
     */
    public AdminUser update(Long id, String role, Boolean enabled) {
        return update(id, null, role, enabled);
    }

    /**
     * 修改姓名 / 角色 / 启用状态。每个参数 {@code null} 表示不修改。
     * <p>
     * 姓名与部门在该用户下次统一身份认证登录时会被认证系统返回的值覆盖
     * （组织信息以认证系统为准）。
     *
     * @param id      主键
     * @param name    姓名，null=不改
     * @param role    角色，null=不改
     * @param enabled 启用状态，null=不改
     * @return 更新后的账号
     */
    public AdminUser update(Long id, String name, String role, Boolean enabled) {
        AdminUser user = AdminUser.findById(id);
        if (user == null) {
            return null;
        }
        if (name != null) {
            user.setName(name.isBlank() ? null : name.trim());
        }
        if (role != null && !role.isBlank()) {
            String normalized = normalizeRole(role);
            user.setRole(normalized);
            user.setRoleName(normalized);
            user.setAllRoles(normalized);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        user.setUpdatedAt(TimestampFill.nowString());
        AdminUser saved = user.save();
        log.info("[admin-user] 更新账号: id={}, role={}, enabled={}", id, user.getRole(), user.getEnabled());
        return saved;
    }

    /** 启用 / 停用 */
    public AdminUser setEnabled(Long id, boolean enabled) {
        return update(id, null, enabled);
    }

    /** 切换 admin / user */
    public AdminUser toggleRole(Long id) {
        AdminUser user = AdminUser.findById(id);
        if (user == null) {
            return null;
        }
        String next = ROLE_ADMIN.equalsIgnoreCase(user.getRole()) ? ROLE_USER : ROLE_ADMIN;
        return update(id, next, null);
    }

    /**
     * 删除账号。最后一个可用管理员不允许删除。
     *
     * @param id 主键
     * @return 删除成功返回 true；不存在或是最后一个可用管理员返回 false
     */
    public boolean delete(Long id) {
        AdminUser user = AdminUser.findById(id);
        if (user == null) {
            return false;
        }
        if (isLastActiveAdmin(user)) {
            log.warn("[admin-user] 拒绝删除最后一个可用管理员: id={}, staffNo={}", id, user.getStaffNo());
            return false;
        }
        AdminUser.self().newQuery().where("id", id).delete();
        log.info("[admin-user] 删除账号: id={}, staffNo={}", id, user.getStaffNo());
        return true;
    }

    /**
     * 判断该账号是否是最后一个可用的管理员。
     * 已停用或非 admin 角色的账号不计入。
     *
     * @param user 待删账号
     * @return 是最后一个返回 true
     */
    private boolean isLastActiveAdmin(AdminUser user) {
        if (!user.isAdmin()) {
            return false;
        }
        long activeAdmins = AdminUser.all().stream().filter(AdminUser::isAdmin).count();
        return activeAdmins <= 1;
    }

    /** 统计：管理员数 / 总数（控制台用） */
    public long countAdmins() {
        return AdminUser.all().stream().filter(AdminUser::isAdmin).count();
    }

    /**
     * 归一化角色值。
     *
     * @param role 输入
     * @return {@code admin} 或 {@code user}
     */
    private static String normalizeRole(String role) {
        if (role == null) {
            return ROLE_USER;
        }
        String trimmed = role.trim();
        return ROLE_ADMIN.equalsIgnoreCase(trimmed) ? ROLE_ADMIN : ROLE_USER;
    }
}
