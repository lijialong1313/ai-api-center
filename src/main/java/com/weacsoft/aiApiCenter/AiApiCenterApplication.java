package com.weacsoft.aiApiCenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口：基于 jaravel-vendor 的多模式 vLLM 排队中间件。
 * <p>
 * 同时支持「单机 / 多机」×「有 Redis / 无 Redis」四种部署组合，
 * 切换成本只有 {@code application.yml} 中的 {@code jaravel.queue.driver} 与 redis 连接配置，
 * 业务代码不分叉。
 */
@SpringBootApplication(scanBasePackages = {
        "com.weacsoft.aiApiCenter",
        "com.weacsoft.jaravel.vendor"
})
public class AiApiCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApiCenterApplication.class, args);
    }
}
