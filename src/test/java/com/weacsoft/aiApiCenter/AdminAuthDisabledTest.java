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
 * 与 {@link AdminBaseIntegrationTest} 的 Spring 上下文配置互斥（认证开 / 关），
 * 必须分成两个测试类。免认证模式下同时要验证控制器在没有真实登录态时不报 500。
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.auth-enabled=false")
class AdminAuthDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccessProperties properties;

    @Test
    @DisplayName("配置项确实读到了 false（确认测试运行在预期模式）")
    void authIsDisabled() {
        org.assertj.core.api.Assertions.assertThat(properties.isAuthEnabled())
                .as("本类应在免认证模式下运行")
                .isFalse();
    }

    @Test
    @DisplayName("免认证：不登录直接访问 /admin 应 200")
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
