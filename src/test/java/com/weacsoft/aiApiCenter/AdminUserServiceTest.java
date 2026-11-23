package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.app.service.AdminUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员账号管理的业务规则测试。
 * <p>
 * 覆盖两条关键规则：
 * <ol>
 *   <li>不能删除最后一个可用管理员；</li>
 *   <li>学工号必须唯一。</li>
 * </ol>
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class AdminUserServiceTest {

    @Autowired
    private AdminUserService adminUserService;

    /** 清掉测试产生的账号，避免跨用例污染 */
    private void cleanup(String... staffNos) {
        for (String staffNo : staffNos) {
            AdminUser user = AdminUser.findByStaffNo(staffNo);
            if (user != null) {
                AdminUser.self().newQuery().where("id", user.getId()).delete();
            }
        }
    }

    @Test
    @DisplayName("创建账号：学工号唯一，重复创建返回 null")
    void createRejectsDuplicateStaffNo() {
        String staffNo = "TEST-DUP-001";
        cleanup(staffNo);
        try {
            AdminUser first = adminUserService.create(staffNo, "张三", "admin");
            assertThat(first).as("首次创建应成功").isNotNull();
            assertThat(first.getStaffNo()).isEqualTo(staffNo);
            assertThat(first.getRole()).isEqualTo("admin");
            assertThat(first.getEnabled()).as("新建默认启用").isTrue();

            AdminUser second = adminUserService.create(staffNo, "李四", "admin");
            assertThat(second)
                    .as("学工号重复必须拒绝")
                    .isNull();
        } finally {
            cleanup(staffNo);
        }
    }

    @Test
    @DisplayName("创建账号：学工号为空返回 null")
    void createRejectsBlankStaffNo() {
        assertThat(adminUserService.create(null, "x", "admin")).isNull();
        assertThat(adminUserService.create("   ", "x", "admin")).isNull();
    }

    @Test
    @DisplayName("角色归一化：非法值按普通用户处理，不会意外提权")
    void roleIsNormalized() {
        String staffNo = "TEST-ROLE-001";
        cleanup(staffNo);
        try {
            AdminUser user = adminUserService.create(staffNo, "测试", "SuperAdmin");
            assertThat(user).isNotNull();
            assertThat(user.getRole())
                    .as("非法角色值应降级为 user")
                    .isEqualTo("user");
        } finally {
            cleanup(staffNo);
        }
    }

    @Test
    @DisplayName("切换角色：admin 与 user 可互相切换，且 roleName/allRoles 同步")
    void toggleRoleKeepsFieldsConsistent() {
        String staffNo = "TEST-TOGGLE-001";
        cleanup(staffNo);
        try {
            AdminUser user = adminUserService.create(staffNo, "测试", "user");
            assertThat(user.getRole()).isEqualTo("user");

            AdminUser promoted = adminUserService.toggleRole(user.getId());
            assertThat(promoted.getRole()).isEqualTo("admin");
            assertThat(promoted.getRoleName())
                    .as("roleName 应与 role 同步").isEqualTo("admin");
            assertThat(promoted.getAllRoles())
                    .as("allRoles 应与 role 同步").isEqualTo("admin");

            AdminUser demoted = adminUserService.toggleRole(user.getId());
            assertThat(demoted.getRole()).isEqualTo("user");
            assertThat(demoted.getRoleName()).isEqualTo("user");
        } finally {
            cleanup(staffNo);
        }
    }

    @Test
    @DisplayName("停用后 isAdmin 为 false（即便角色仍是 admin）")
    void disabledAdminIsNotUsable() {
        String staffNo = "TEST-DISABLE-001";
        cleanup(staffNo);
        try {
            AdminUser user = adminUserService.create(staffNo, "测试", "admin");
            assertThat(user.isAdmin()).as("启用状态下应为可用管理员").isTrue();

            adminUserService.setEnabled(user.getId(), false);
            AdminUser reloaded = adminUserService.get(user.getId());
            assertThat(reloaded.getRole()).as("角色字段不变").isEqualTo("admin");
            assertThat(reloaded.isAdmin())
                    .as("停用后不应算作可用管理员")
                    .isFalse();
        } finally {
            cleanup(staffNo);
        }
    }

    @Test
    @DisplayName("不能删除最后一个可用管理员")
    void cannotDeleteLastActiveAdmin() {
        // 自造前提：先把其他管理员临时停用，使本用例有一个确定的初始状态，
        // 结束时恢复。
        String staffNo = "TEST-LAST-001";
        cleanup(staffNo);

        // 记录并临时停用其他管理员，保证本用例有确定的初始状态
        List<AdminUser> others = adminUserService.list().stream()
                .filter(AdminUser::isAdmin)
                .filter(u -> !staffNo.equals(u.getStaffNo()))
                .toList();
        for (AdminUser other : others) {
            adminUserService.setEnabled(other.getId(), false);
        }

        AdminUser created = adminUserService.create(staffNo, "测试", "admin");
        try {
            assertThat(created).isNotNull();
            // 此刻全库只有它一个可用管理员
            assertThat(adminUserService.countAdmins())
                    .as("前置条件：当前应只有 1 个可用管理员").isEqualTo(1);

            boolean deleted = adminUserService.delete(created.getId());
            assertThat(deleted)
                    .as("最后一个可用管理员不应被删除")
                    .isFalse();
            assertThat(adminUserService.get(created.getId()))
                    .as("拒绝删除后账号应仍在").isNotNull();

            // 再验证「不是一律拒绝」：停用它之后它就不可用了，
            // 此时删除应当成功（因为它不再是可用管理员，删了不影响可用性）
            adminUserService.setEnabled(created.getId(), false);
            assertThat(adminUserService.delete(created.getId()))
                    .as("已停用的账号不再算可用管理员，应当允许删除")
                    .isTrue();
        } finally {
            cleanup(staffNo);
            // 恢复被临时停用的管理员
            for (AdminUser other : others) {
                adminUserService.setEnabled(other.getId(), true);
            }
        }
    }

    @Test
    @DisplayName("删除不存在的账号返回 false")
    void deleteMissingReturnsFalse() {
        assertThat(adminUserService.delete(-99999L)).isFalse();
    }

    @Test
    @DisplayName("countAdmins 只数可用管理员（启用 + admin）")
    void countAdminsCountsOnlyUsable() {
        String staffA = "TEST-CNT-A";
        String staffB = "TEST-CNT-B";
        cleanup(staffA, staffB);
        try {
            long before = adminUserService.countAdmins();

            adminUserService.create(staffA, "A", "admin");
            assertThat(adminUserService.countAdmins()).as("加一个管理员应 +1").isEqualTo(before + 1);

            AdminUser b = adminUserService.create(staffB, "B", "admin");
            assertThat(adminUserService.countAdmins()).isEqualTo(before + 2);

            adminUserService.setEnabled(b.getId(), false);
            assertThat(adminUserService.countAdmins())
                    .as("停用后不应再计入").isEqualTo(before + 1);
        } finally {
            cleanup(staffA, staffB);
        }
    }
}
