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
 * <b>与统一身份认证的关系</b>：正常情况下管理员账号是「第一次用 SSO 登录时自动开通」的
 * （见 {@link UserProvisionService}），不需要人工创建。但这个管理界面仍然必要，原因是
 * 现实中经常需要<b>预先授权</b>：
 * <ul>
 *   <li>某人还没登录过，但要先给他管理员权限，等他第一次登录就生效；</li>
 *   <li>某人已登录但角色判断没命中（比如他的 {@code allRole} 不在
 *       {@code app.sso.admin-roles} 里），需要单独提权；</li>
 *   <li>离职 / 转岗时立即停用，而不是等他自己来登录才被拦。</li>
 * </ul>
 * <p>
 * <b>权限提升是显式的</b>：这里把 {@code role} 改成 {@code admin} 是唯一的提权途径，
 * 且只对已经存在于库里的账号有效。未登录过的人需要先由人工建立记录（填学工号），
 * 等他首次 SSO 登录时会按学工号匹配到这条记录，不会被重新创建成普通用户。
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
     * 避免界面上出现「role=admin 但 allRoles 显示学生」这种自相矛盾。
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
     * 修改姓名 / 角色 / 启用状态。
     * <p>
     * 每个参数 {@code null} 表示不修改。姓名允许清空（存 null）。
     * <p>
     * 注意：姓名和部门这类字段在用户下次 SSO 登录时会被认证系统返回的值覆盖——
     * 那是设计如此（组织信息以认证系统为准）。所以这里改的姓名主要用于
     * 「还没登录过的预先授权账号」，以及临时备注。
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
            // 保持一致：否则界面上「角色=管理员」而「全部角色=学生」会让人无法判断实际权限
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
     * 删除账号。
     * <p>
     * <b>会阻止删最后一个可用管理员</b>：否则一旦删光，在认证开启的情况下
     * 将无法再进入管理后台（除非直接修改数据库），形成难以恢复的阻塞。
     *
     * @param id 主键
     * @return 删除成功返回 true；不存在或是最后一个管理员返回 false
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
     * 判断该账号是否是「最后一个可用的管理员」。
     * <p>
     * 停用的、非 admin 的都不算数——只有还能真正登录并管理后台的才算。
     *
     * @param user 待删账号
     * @return 是最后一个返回 true
     */
    private boolean isLastActiveAdmin(AdminUser user) {
        if (!user.isAdmin()) {
            return false;   // 本来就不是可用管理员，删了不影响
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
