package com.weacsoft.aiApiCenter.app.model;

import com.weacsoft.jaravel.vendor.auth.contract.Authenticatable;
import com.weacsoft.jaravel.vendor.database.BaseModel;
import com.weacsoft.jaravel.vendor.database.TimestampFill;
import gaarason.database.annotation.Column;
import gaarason.database.annotation.Primary;
import gaarason.database.annotation.Table;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 管理员账号，对齐 {@code admin_users} 表。
 * <p>
 * <b>没有口令字段</b>：管理员登录走统一身份认证（OAuth2 授权码），
 * 本系统只保存认证系统返回的身份信息，因此 {@code auth} 模块「密码校验与应用层解耦」
 * 的设计在这里天然成立——我们根本不校验密码。
 * <p>
 * 主键即 {@code Auth} 的比对依据（{@link #getAuthIdentifier()}），
 * 统一身份认证成功后由 {@code UserProvisionService} 按学工号 {@code upsert} 出本实体，
 * 再 {@code Auth.login(user)} 签发 JWT。
 */
@Repository
@Table(name = "admin_users")
public class AdminUser extends BaseModel<AdminUser, Long> implements Authenticatable {

    @Primary
    @Column(name = "id")
    private Long id;

    /** 学工号（统一身份认证返回的 number/uid），账号的唯一稳定标识 */
    @Column(name = "staff_no")
    private String staffNo;

    @Column(name = "name")
    private String name;

    @Column(name = "college")
    private String college;

    @Column(name = "role_name")
    private String roleName;

    /** 逗号分隔的全部角色，如「教师,学生」 */
    @Column(name = "all_roles")
    private String allRoles;

    /** 本系统角色：admin / user */
    @Column(name = "role")
    private String role;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "last_login_at")
    private String lastLoginAt;

    /**
     * 创建时间。
     * <p>
     * 用 {@code String} + jaravel 的 {@code TimestampFill} 而不是 {@code LocalDateTime}：
     * gaarason 会把 {@code LocalDateTime} 序列化成 ISO 形式（{@code 2026-11-20T22:57:29.97}），
     * 而 SQLite 的 {@code getTimestamp()} 只认 {@code yyyy-MM-dd HH:mm:ss}，读回时会抛
     * {@code Error parsing time stamp}。用字符串 + 统一格式，跨 SQLite/MySQL 行为一致。
     */
    @Column(name = "created_at", fill = TimestampFill.CreatedTimeStringFill.class)
    private String createdAt;

    @Column(name = "updated_at", fill = TimestampFill.UpdatedTimeStringFill.class)
    private String updatedAt;

    /** 静态访问入口（对齐 {@code User::self()}） */
    public static AdminUser self() {
        return BaseModel.self(AdminUser.class);
    }

    /**
     * 按主键查（对齐 {@code Model::find()}）。
     * <p>
     * 方法名刻意不用 {@code find}：gaarason 的 {@code Model} 已有同名<b>实例</b>方法，
     * Java 不允许静态方法与继承的实例方法同名同参，会编译失败。
     */
    public static AdminUser findById(Long id) {
        if (id == null) {
            return null;
        }
        // find() 在无匹配记录时同样返回 null
        gaarason.database.contract.eloquent.Record<AdminUser, Long> record =
                AdminUser.self().find(id);
        return record == null ? null : record.toObject();
    }

    /** 按学工号查（统一身份认证登录后按此匹配本地账号） */
    public static AdminUser findByStaffNo(String staffNo) {
        if (staffNo == null || staffNo.isBlank()) {
            return null;
        }
        // 注意：gaarason 的 first() 在无匹配记录时返回 null，不是空 Record，必须先判空
        gaarason.database.contract.eloquent.Record<AdminUser, Long> record =
                AdminUser.self().newQuery().where("staff_no", staffNo).first();
        return record == null ? null : record.toObject();
    }

    /** 全部账号（管理后台列表用） */
    public static List<AdminUser> all() {
        return AdminUser.self().newQuery()
                .orderBy("id", gaarason.database.appointment.OrderBy.DESC)
                .get()
                .toObjectList();
    }

    // ---------------- Authenticatable ----------------

    @Override
    public Object getAuthIdentifier() {
        return id;
    }

    @Override
    public String getAuthIdentifierName() {
        return "id";
    }

    /** 是否为管理员（{@code role=admin} 且未被禁用） */
    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role) && Boolean.TRUE.equals(enabled);
    }

    /** 账号是否可用 */
    public boolean isActive() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 全部角色拆分（用于页面展示） */
    public List<String> roleList() {
        if (allRoles == null || allRoles.isBlank()) {
            return List.of();
        }
        return List.of(allRoles.split(","));
    }

    // ---------------- getter / setter ----------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStaffNo() { return staffNo; }
    public void setStaffNo(String staffNo) { this.staffNo = staffNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCollege() { return college; }
    public void setCollege(String college) { this.college = college; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public String getAllRoles() { return allRoles; }
    public void setAllRoles(String allRoles) { this.allRoles = allRoles; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(String lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
