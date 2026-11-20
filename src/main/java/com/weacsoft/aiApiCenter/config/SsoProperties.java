package com.weacsoft.aiApiCenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一身份认证配置（广州城市理工学院智慧校园认证系统 OAuth2.0，文档 v1.0.4）。
 * <p>
 * 对应 {@code application.yml} 的 {@code app.sso.*}。文档里提到的 {@code client_id}
 * 就是「key」，{@code client_secret} 就是「secret」。
 */
@Component
@ConfigurationProperties(prefix = "app.sso")
public class SsoProperties {

    /** 是否启用统一身份认证登录；关闭时登录页给出提示（用于本地开发） */
    private boolean enabled = true;

    /** 认证系统根地址，如 https://auth.gcu.edu.cn */
    private String baseUrl = "https://auth.gcu.edu.cn";

    /** 系统 id（文档中的 client_id / key） */
    private String clientId = "";

    /** 系统密钥（文档中的 client_secret / secret） */
    private String clientSecret = "";

    /**
     * 认证系统回调本系统的地址。
     * <p>
     * 必须与认证系统登记的一致；留空则按当前请求自动推导（便于部署在虚拟目录下）。
     */
    private String redirectUri = "";

    /** 登录成功后由认证系统回跳的业务地址（callbackUrl），默认管理后台首页 */
    private String callbackUrl = "/admin";

    /** 允许登录管理后台的角色，{@code allRole} 中任一命中即可（逗号分隔） */
    private String adminRoles = "教师,教职工";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }

    public String getAdminRoles() { return adminRoles; }
    public void setAdminRoles(String adminRoles) { this.adminRoles = adminRoles; }

    /** 是否已配置好 client_id / client_secret（未配置时无法跳认证） */
    public boolean isConfigured() {
        return enabled
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
