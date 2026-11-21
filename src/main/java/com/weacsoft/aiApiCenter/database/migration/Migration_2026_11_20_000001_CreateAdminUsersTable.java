package com.weacsoft.aiApiCenter.database.migration;

import com.weacsoft.jaravel.vendor.migration.Migration;
import com.weacsoft.jaravel.vendor.migration.MigrationAnnotation;
import com.weacsoft.jaravel.vendor.migration.Schema;

/**
 * 建管理员账号表 {@code admin_users}。
 * <p>
 * 管理员登录走<b>统一身份认证</b>（OAuth2 授权码），本系统不保存口令，
 * 因此这里没有 {@code password_hash}；{@code staff_no} 为统一身份认证返回的学工号，
 * 是账号的唯一稳定标识。{@code role} 用于区分管理员 / 普通用户。
 */
@MigrationAnnotation
public class Migration_2026_11_20_000001_CreateAdminUsersTable implements Migration {

    static final String TABLE = "admin_users";

    @Override
    public void up(Schema schema) {
        schema.create(TABLE, table -> {
            table.id();
            table.string("staff_no", 64).unique();
            table.string("name", 128).nullable();
            table.string("college", 255).nullable();
            table.string("role_name", 64).nullable();
            table.string("all_roles", 512).nullable();
            table.string("role", 16).notNull().defaultValue("user");
            table.booleanColumn("enabled").notNull().defaultValue(true);
            table.timestamp("last_login_at").nullable();
            table.timestamps();
            // 软删除列：BaseModel 统一启用软删除（softDeleting() 恒 true），
            // newQuery() 会无条件附加 "deleted_at IS NULL"。
            // 因此每张继承 BaseModel 的表都必须有这一列，否则查询报
            //   no such column: xxx.deleted_at
            // 业务上若不需要「回收站」语义，日常查询看到的永远只是未删除行，
            // 行为等同于没有软删除，只是多一列、条件更明确。
            table.softDeletes();
        });
    }

    @Override
    public void down(Schema schema) {
        schema.dropIfExists(TABLE);
    }
}
