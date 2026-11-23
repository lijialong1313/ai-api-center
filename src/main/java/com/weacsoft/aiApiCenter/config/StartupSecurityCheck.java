package com.weacsoft.aiApiCenter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时的安全自检。
 * <p>
 * 当 {@code app.admin.auth-enabled=false} 时，在启动日志输出安全告警。
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
