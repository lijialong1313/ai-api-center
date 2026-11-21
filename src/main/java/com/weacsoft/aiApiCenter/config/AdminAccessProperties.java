package com.weacsoft.aiApiCenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理后台访问控制开关。
 * <p>
 * <b>为什么要有开关</b>：统一身份认证需要学校分配的 {@code client-id}/{@code client-secret}，
 * 在没有凭据的阶段（或需要本地快速试用管理界面时），把认证中间件摘掉才能进得去。
 * 但这是<b>临时</b>手段——一旦对外暴露，任何人都能改 API Key 与后端地址。
 * <p>
 * 因此设计成配置项而不是删代码：
 * <ul>
 *   <li>{@code true}（默认）—— 完整校验：先 {@code AdminAuthenticate} 再 {@code RequireAdmin}；</li>
 *   <li>{@code false} —— 完全放行，仅用于本地联调，启动时会打醒目告警。</li>
 * </ul>
 * 配置项：{@code app.admin.auth-enabled}
 */
@Component
@ConfigurationProperties(prefix = "app.admin")
public class AdminAccessProperties {

    /**
     * 是否启用管理后台认证与授权。
     * <p>
     * 默认 {@code true}（安全优先）。设为 {@code false} 会跳过登录与管理员校验，
     * <b>只应在本机联调时使用</b>。
     */
    private boolean authEnabled = true;

    /**
     * 关闭认证时，管理后台访问者使用的虚拟管理员学工号。
     * <p>
     * 用于页面右上角展示与 {@code /admin/me} 返回，让界面不至于因为「没有当前用户」而报错。
     * 这个账号不会写库，也不是真实登录态。
     */
    private String mockAdminStaffNo = "local-dev";

    /** 关闭认证时展示的名称 */
    private String mockAdminName = "本地调试（未启用认证）";

    public boolean isAuthEnabled() { return authEnabled; }
    public void setAuthEnabled(boolean authEnabled) { this.authEnabled = authEnabled; }

    public String getMockAdminStaffNo() { return mockAdminStaffNo; }
    public void setMockAdminStaffNo(String mockAdminStaffNo) { this.mockAdminStaffNo = mockAdminStaffNo; }

    public String getMockAdminName() { return mockAdminName; }
    public void setMockAdminName(String mockAdminName) { this.mockAdminName = mockAdminName; }
}
