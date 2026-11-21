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
 * 重点不在 CRUD 本身，而在两条<b>会把人锁在门外</b>的规则：
 * <ol>
 *   <li>不能删掉最后一个可用管理员 —— 否则认证一开，谁都进不了后台；</li>
 *   <li>学工号必须唯一 —— 否则同一个人会有两条权限记录，出现「停用了这条、那条还能用」。</li>
 * </ol>
 * 这两条都是「写错了平时看不出来、出事时很难救」的类型，必须有测试钉住。
 */
@SpringBootTest
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
                    .as("学工号重复必须拒绝，否则会有两条权限记录互相矛盾")
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
                    .as("非法角色值必须降级为 user，绝不能因为拼错就提权")
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
                    .as("roleName 必须跟着变，否则界面显示与权限不一致").isEqualTo("admin");
            assertThat(promoted.getAllRoles())
                    .as("allRoles 必须跟着变").isEqualTo("admin");

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
                    .as("停用后不应算作可用管理员 —— 否则「停用」形同虚设")
                    .isFalse();
        } finally {
            cleanup(staffNo);
        }
    }

    @Test
    @DisplayName("不能删除最后一个可用管理员（认证开启后会把所有人锁在门外）")
    void cannotDeleteLastActiveAdmin() {
        // 先记录现有管理员数量，避免破坏环境里已有数据
        List<AdminUser> before = adminUserService.list();
        boolean onlyOne = before.stream().filter(AdminUser::isAdmin).count() <= 1;

        String staffNo = "TEST-LAST-001";
        cleanup(staffNo);
        AdminUser created = adminUserService.create(staffNo, "测试", "admin");
        try {
            if (onlyOne) {
                // 环境里本来就只有这一个管理员：删除必须被拒绝
                boolean deleted = adminUserService.delete(created.getId());
                assertThat(deleted)
                        .as("最后一个可用管理员绝不能被删除")
                        .isFalse();
                assertThat(adminUserService.get(created.getId()))
                        .as("拒绝删除后账号应仍在").isNotNull();
            } else {
                // 环境里有多个管理员：删这个应当成功（验证拒绝条件不是「一律拒绝」）
                boolean deleted = adminUserService.delete(created.getId());
                assertThat(deleted)
                        .as("存在其他管理员时，删掉一个应当允许")
                        .isTrue();
            }
        } finally {
            cleanup(staffNo);
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
