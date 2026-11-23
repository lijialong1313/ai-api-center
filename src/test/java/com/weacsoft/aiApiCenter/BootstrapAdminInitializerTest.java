package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动管理员预置机制（{@code app.admin.bootstrap-admin-staff-no}）的测试。
 * <p>
 * 通过 {@code @TestPropertySource} 指定工号，走真实的 ApplicationRunner 路径。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.admin.bootstrap-admin-staff-no=BOOTSTRAP-001",
        "app.admin.bootstrap-admin-name=启动管理员"
})
class BootstrapAdminInitializerTest {

    private static final String STAFF_NO = "BOOTSTRAP-001";

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("bootstrapAdminRunner")
    private ApplicationRunner bootstrapAdminRunner;

    @BeforeEach
    void prepare() throws Exception {
        removeAccount();
        runBootstrap();
    }

    @AfterEach
    void cleanup() {
        removeAccount();
    }

    @Test
    @DisplayName("配置的工号在启动时被创建为管理员")
    void bootstrapAdminIsCreated() {
        AdminUser created = AdminUser.findByStaffNo(STAFF_NO);
        assertThat(created).as("启动时应创建该工号的管理员").isNotNull();
        assertThat(created.getRole()).isEqualTo("admin");
        assertThat(created.isAdmin()).as("创建的账号应可直接使用").isTrue();
        assertThat(created.getName()).isEqualTo("启动管理员");
        assertThat(created.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("已存在的账号不被覆盖：降权并停用后再次启动仍保持原状态")
    void existingAccountIsNotOverwritten() throws Exception {
        AdminUser existing = AdminUser.findByStaffNo(STAFF_NO);
        assertThat(existing).isNotNull();
        existing.setRole("user");
        existing.setEnabled(false);
        existing.save();

        runBootstrap();

        AdminUser reloaded = AdminUser.findByStaffNo(STAFF_NO);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getRole()).as("角色不应被启动流程改回 admin").isEqualTo("user");
        assertThat(reloaded.getEnabled()).as("启用状态不应被启动流程改回 true").isFalse();
    }

    /** 执行一次启动预置流程 */
    private void runBootstrap() throws Exception {
        bootstrapAdminRunner.run(args());
    }

    private ApplicationArguments args() {
        return new DefaultApplicationArguments(new String[0]);
    }

    /** 删除测试账号，保证用例之间互不影响 */
    private void removeAccount() {
        AdminUser user = AdminUser.findByStaffNo(STAFF_NO);
        if (user != null) {
            AdminUser.self().newQuery().where("id", user.getId()).delete();
        }
    }
}
