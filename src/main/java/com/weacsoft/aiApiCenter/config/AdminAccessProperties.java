package com.weacsoft.aiApiCenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理后台访问控制配置（{@code app.admin.*}）。
 */
@Component
@ConfigurationProperties(prefix = "app.admin")
public class AdminAccessProperties {

    /**
     * 是否启用管理后台认证与授权。
     * <p>
     * {@code true}：完整校验，先 {@code AdminAuthenticate} 再 {@code RequireAdmin}；
     * {@code false}：跳过登录与管理员校验，仅限本机联调，启动时输出安全告警。
     */
    private boolean authEnabled = true;

    /**
     * 启动时确保存在的管理员工号。
     * <p>
     * 库中不存在该工号时，以管理员角色创建（预先授权，首次统一身份认证登录按工号匹配生效）；
     * 已存在时不做任何修改。留空表示禁用本机制。
     */
    private String bootstrapAdminStaffNo = "2019095";

    /** 启动时创建管理员工号的显示名称，留空时使用工号本身 */
    private String bootstrapAdminName = "";

    /**
     * 关闭认证时，管理后台访问者使用的虚拟管理员学工号。
     * <p>
     * 仅用于页面展示与 {@code /admin/me} 返回，不写库，也不是登录态。
     */
    private String mockAdminStaffNo = "2019095";

    /** 关闭认证时展示的名称 */
    private String mockAdminName = "本地调试（未启用认证）";

    public boolean isAuthEnabled() { return authEnabled; }
    public void setAuthEnabled(boolean authEnabled) { this.authEnabled = authEnabled; }

    public String getBootstrapAdminStaffNo() { return bootstrapAdminStaffNo; }
    public void setBootstrapAdminStaffNo(String bootstrapAdminStaffNo) { this.bootstrapAdminStaffNo = bootstrapAdminStaffNo; }

    public String getBootstrapAdminName() { return bootstrapAdminName; }
    public void setBootstrapAdminName(String bootstrapAdminName) { this.bootstrapAdminName = bootstrapAdminName; }

    public String getMockAdminStaffNo() { return mockAdminStaffNo; }
    public void setMockAdminStaffNo(String mockAdminStaffNo) { this.mockAdminStaffNo = mockAdminStaffNo; }

    public String getMockAdminName() { return mockAdminName; }
    public void setMockAdminName(String mockAdminName) { this.mockAdminName = mockAdminName; }
}
