package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.model.AdminUser;
import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.model.BackendPool;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import com.weacsoft.aiApiCenter.app.service.BackendPoolService;
import com.weacsoft.aiApiCenter.app.service.SsoService;
import com.weacsoft.jaravel.vendor.auth.facade.Auth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台基座的集成测试：认证闸门、JWT 令牌、三个管理界面的数据通路。
 * <p>
 * 用 MockMvc 走真实的 Spring MVC + jaravel 路由管线，包括中间件链，
 * 所以同时验证了「路由注册」与「中间件生效」两件事。
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
// 显式开启认证：本类验证的是「认证闸门」本身的行为（未登录 302、非管理员 403）。
// 不写死默认值，否则一旦有人把 app.admin.auth-enabled 的默认值改成 false，
// 这些断言会集体失效却不说明原因。
@TestPropertySource(properties = "app.admin.auth-enabled=true")
class AdminBaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private BackendPoolService backendPoolService;

    @Autowired
    private com.weacsoft.jaravel.vendor.auth.AuthManager authManager;

    private Long adminId;

    @BeforeEach
    void setUp() {
        // 统一身份认证已经成功的等价状态：本地已有一个管理员账号
        AdminUser existing = AdminUser.findByStaffNo("T0001");
        AdminUser admin = existing != null ? existing : new AdminUser();
        admin.setStaffNo("T0001");
        admin.setName("测试管理员");
        admin.setCollege("信息中心");
        admin.setRoleName("教师");
        admin.setAllRoles("教师");
        admin.setRole("admin");
        admin.setEnabled(true);
        AdminUser saved = admin.save();
        this.adminId = saved != null ? saved.getId() : admin.getId();
        assertThat(adminId).isNotNull();
    }

    @Test
    @DisplayName("未登录访问 /admin 重定向到登录页")
    void unauthenticatedAdminRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/login"));

        mockMvc.perform(get("/admin/api-keys"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/login"));

        mockMvc.perform(get("/admin/backends"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/login"));
    }

    @Test
    @DisplayName("登录页可渲染（未配置 SSO 时给出明确提示而非白屏）")
    void loginPageRenders() throws Exception {
        String html = mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("管理员登录");
        // 未配置 client_id/secret 时必须提示配置缺失，不能静默失败
        assertThat(html).contains("统一身份认证");
    }

    @Test
    @DisplayName("健康探针可用")
    void healthEndpoint() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("持有效 JWT 时可访问控制台/Key管理/后端管理")
    void authenticatedCanAccessAdminPages() throws Exception {
        String token = loginAndGetToken();

        String dashboard = mockMvc.perform(get("/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains("控制台");
        assertThat(dashboard).contains("测试管理员");

        String keys = mockMvc.perform(get("/admin/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(keys).contains("API Key 管理");

        String backends = mockMvc.perform(get("/admin/backends")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(backends).contains("GPU 后端管理");
    }

    @Test
    @DisplayName("JWT 也可通过 Cookie 传递（浏览器访问后台的形态）")
    void tokenViaCookie() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(get("/admin").cookie(new jakarta.servlet.http.Cookie(
                        "ac_admin_token", token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非管理员登录后被 403 拦下（认证不等于授权）")
    void nonAdminIsForbidden() throws Exception {
        // 造一个非管理员账号（普通学生角色）
        AdminUser normal = AdminUser.findByStaffNo("S0001");
        if (normal == null) {
            normal = new AdminUser();
            normal.setStaffNo("S0001");
        }
        normal.setName("普通学生");
        normal.setRoleName("学生");
        normal.setAllRoles("学生");
        normal.setRole("user");
        normal.setEnabled(true);
        AdminUser savedNormal = normal.save();

        String token = issueToken(savedNormal);

        mockMvc.perform(get("/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /**
     * 签发一个真实可用的 JWT。
     * <p>
     * 关键：签发后必须 {@link AuthManager#clear()} 清掉本线程缓存的守卫实例，
     * 否则 MockMvc 在同一线程内会直接复用「已登录」的守卫，根本没走 token 校验，
     * 测试就变成了自欺欺人。清掉之后，请求里的守卫是全新的，必须真的解析并校验
     * Authorization 头里的 token 才能通过——这才是端到端验证。
     */
    private String issueToken(AdminUser user) {
        authManager.clear();
        authManager.login(user, "admin");
        String token = authManager.token("admin");
        assertThat(token).isNotBlank();
        authManager.clear();
        return token;
    }

    private String loginAndGetToken() {
        AdminUser admin = AdminUser.findById(adminId);
        assertThat(admin).as("管理员账号应存在").isNotNull();
        return issueToken(admin);
    }

    @Test
    @DisplayName("API Key：创建后明文可解析回实体，禁用后立即不可用")
    void apiKeyLifecycle() {
        ApiKeyService.CreatedKey created = apiKeyService.create("集成测试", 5);
        assertThat(created.plaintext()).startsWith("sk-ac-");
        assertThat(created.key().getWeight()).isEqualTo(5);

        // 明文能解析出可用 Key（准入鉴权路径）
        ApiKey resolved = apiKeyService.resolveUsable(created.plaintext());
        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo(created.key().getId());

        // 禁用后立即不可用
        apiKeyService.setEnabled(created.key().getId(), false);
        assertThat(apiKeyService.resolveUsable(created.plaintext())).isNull();

        // 库中不存明文
        ApiKey stored = apiKeyService.get(created.key().getId());
        assertThat(stored.getKeyHash()).isNotEqualTo(created.plaintext());
        assertThat(stored.getKeyPrefix()).isEqualTo(created.plaintext().substring(0, 11));

        apiKeyService.delete(created.key().getId());
        assertThat(apiKeyService.get(created.key().getId())).isNull();
    }

    @Test
    @DisplayName("GPU 后端：容量下限为 1，后端不可达时健康检查如实标记 UNHEALTHY")
    void backendLifecycle() {
        BackendPool pool = backendPoolService.create("test-gpu", "测试后端",
                "http://127.0.0.1:59999/", 0, null);
        // 容量不能为 0（否则调度器永远不会放行）
        assertThat(pool.getCapacity()).isEqualTo(1);
        // 末尾斜杠被归一化
        assertThat(pool.getBaseUrl()).isEqualTo("http://127.0.0.1:59999");
        assertThat(pool.getLastHealthStatus()).isEqualTo(BackendPool.HEALTH_UNKNOWN);

        BackendPoolService.HealthResult result = backendPoolService.healthCheck(pool.getId());
        assertThat(result).isNotNull();
        assertThat(result.healthy()).isFalse();
        assertThat(backendPoolService.get(pool.getId()).getLastHealthStatus())
                .isEqualTo(BackendPool.HEALTH_UNHEALTHY);

        backendPoolService.delete(pool.getId());
        assertThat(backendPoolService.get(pool.getId())).isNull();
    }

    @Test
    @DisplayName("统一身份认证接口登录的口令加密与文档示例一致")
    void desEncryptMatchesDocumentSample() {
        // 文档 v1.0.4 给出的测试向量：data=test, key=1234 → hR9QuTk9tq8=
        assertThat(SsoService.desEncrypt("test", "1234")).isEqualTo("hR9QuTk9tq8=");
        // data=test, key=hR9QuTk9tq8= → Y6D9J+UlQkM=
        assertThat(SsoService.desEncrypt("test", "hR9QuTk9tq8=")).isEqualTo("Y6D9J+UlQkM=");
    }

    @Test
    @DisplayName("可调度 Key 只包含启用且权重>0 的项")
    void schedulableKeysExcludeDisabled() {
        ApiKey ok = apiKeyService.create("可调度", 3).key();
        ApiKey disabled = apiKeyService.create("已禁用", 1).key();
        apiKeyService.setEnabled(disabled.getId(), false);

        List<ApiKey> schedulable = ApiKey.schedulable();
        List<Long> ids = schedulable.stream().map(ApiKey::getId).toList();
        assertThat(ids).contains(ok.getId());
        assertThat(ids).doesNotContain(disabled.getId());

        apiKeyService.delete(ok.getId());
        apiKeyService.delete(disabled.getId());
    }
}
