package com.weacsoft.aiApiCenter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时的安全自检。
 * <p>
 * 目前只做一件事：当管理后台认证被关闭时，在启动日志里打一条<b>不可能被忽略</b>的告警。
 * <p>
 * 为什么要在启动时就喊：{@code app.admin.auth-enabled=false} 之后，
 * 任何能访问到该端口的人都可以创建/删除 API Key、改 GPU 后端地址、看到全部配置。
 * 这类「为了本地联调临时关掉、后来忘了」的设置是真实事故的常见来源，
 * 所以把提醒放在每次启动必经的路径上，而不是写在文档里等人去读。
 */
@Configuration
public class StartupSecurityCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    @Bean
    public ApplicationRunner adminAuthSafetyNotice(AdminAccessProperties props) {
        return args -> {
            if (props.isAuthEnabled()) {
                log.info("[security] 管理后台认证：已启用（统一身份认证 + 管理员校验）");
                return;
            }
            log.warn("");
            log.warn("==================== 安全警告 ====================");
            log.warn("管理后台认证已【关闭】：app.admin.auth-enabled=false");
            log.warn("任何能访问本端口的人都可以：");
            log.warn("  · 创建 / 删除 API Key（等于拿到调用模型的权限）");
            log.warn("  · 修改 GPU 后端地址与容量（可把流量导到任意地址）");
            log.warn("  · 查看全部配置");
            log.warn("这只应在本机联调时使用。对外暴露前必须设：");
            log.warn("  ADMIN_AUTH_ENABLED=true  （或 app.admin.auth-enabled: true）");
            log.warn("================================================");
            log.warn("");
        };
    }
}
