package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.config.AdminAccessProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 免认证模式（{@code app.admin.auth-enabled=false}）的行为测试。
 * <p>
 * <b>为什么单独一个类</b>：这与 {@link AdminBaseIntegrationTest} 是两套互斥的语义——
 * 那边验证「没登录就进不去」，这边验证「不登录也能进」。两者的 Spring 上下文配置不同
 * （不同的 property），必须分开，否则会互相覆盖属性。
 * <p>
 * 这个模式是为「学校还没分配 SSO 的 client-id / client-secret，但需要先把后台跑起来看」
 * 准备的。它<b>不安全</b>，因此同时要验证：控制器在「没有真实登录态」时也不会 500
 * （页面要显示当前用户，取不到就崩是很容易踩的坑）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.auth-enabled=false")
class AdminAuthDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccessProperties properties;

    @Test
    @DisplayName("配置项确实读到了 false（防止测试跑在错误的模式上）")
    void authIsDisabled() {
        org.assertj.core.api.Assertions.assertThat(properties.isAuthEnabled())
                .as("本类必须在免认证模式下运行，否则以下断言没有意义")
                .isFalse();
    }

    @Test
    @DisplayName("免认证：不登录直接访问 /admin 应 200，而不是 302 到登录页")
    void adminHomeIsAccessibleWithoutLogin() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("免认证：API Key 管理页可访问")
    void apiKeysPageIsAccessibleWithoutLogin() throws Exception {
        mockMvc.perform(get("/admin/api-keys")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("免认证：GPU 后端管理页可访问")
    void backendsPageIsAccessibleWithoutLogin() throws Exception {
        mockMvc.perform(get("/admin/backends")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("免认证：管理员管理页可访问（模板变量写错会 500）")
    void usersPageIsAccessibleWithoutLogin() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("免认证：/admin/me 返回虚拟管理员而不是 401（页面靠它显示当前用户）")
    void meReturnsMockAdmin() throws Exception {
        mockMvc.perform(get("/admin/me"))
                .andExpect(status().isOk());
    }
}
