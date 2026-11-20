package com.weacsoft.aiApiCenter.config;

import com.weacsoft.jaravel.vendor.core.SpringContext;
import com.weacsoft.jaravel.vendor.database.ConnectionManager;
import com.weacsoft.jaravel.vendor.database.RegisterConnection;
import gaarason.database.bootstrap.ContainerBootstrap;
import gaarason.database.connection.GaarasonDataSourceBuilder;
import gaarason.database.contract.connection.GaarasonDataSource;
import gaarason.database.provider.ModelInstanceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 数据库配置，对齐 Laravel {@code config/database.php}。
 * <p>
 * 默认连接别名 {@code sqlite}（单机零配置，文件库），Model 未标注 {@code @DataSource} 时使用。
 * 生产环境把 {@code spring.datasource.*} 换成 MySQL 即可，业务代码无需改动。
 * <p>
 * <b>ContainerBootstrap 只能有一个</b>：gaarason 要求所有 {@code GaarasonDataSource} 共用同一实例，
 * 否则 Model 注册表会分裂到不同容器导致查询报错。因此这里只创建一次并交给框架的
 * {@link ConnectionManager} 保管。
 */
@Configuration
public class DatabaseConfig {

    /**
     * 创建并初始化全局唯一的 gaarason 容器。
     * <p>
     * 注册自定义 {@link ModelInstanceProvider}，使 gaarason 需要 Model 实例时
     * 从 Spring 容器获取托管单例（这样 Model 上的 {@code @Autowired} 依赖才生效）。
     */
    @Bean
    public ContainerBootstrap containerBootstrap(@Autowired Environment env) {
        String scanPackages = env.getProperty("gaarason.database.scan.packages",
                "com.weacsoft.aiApiCenter.app.model");
        if (System.getProperty("gaarason.database.scan.packages") == null) {
            System.setProperty("gaarason.database.scan.packages", scanPackages);
        }

        ContainerBootstrap bootstrap = ContainerBootstrap.build();
        bootstrap.defaultRegister();

        ModelInstanceProvider modelInstanceProvider = bootstrap.getBean(ModelInstanceProvider.class);
        modelInstanceProvider.register(modelClass -> SpringContext.bean(modelClass));

        bootstrap.bootstrapGaarasonAutoconfiguration();
        bootstrap.initialization();

        // 存入框架门面，保证全框架自始至终使用同一个 ContainerBootstrap
        ConnectionManager.setContainer(bootstrap);
        return bootstrap;
    }

    /**
     * 默认连接（别名 {@code sqlite}）。
     * <p>
     * SQLite 的 {@code ENUM}/{@code 自增} 语法与 MySQL 差异较大，这里复用 weacsoft 提供的
     * Sqlite 方言包完成包装，其余数据库走 gaarason 原生 builder。
     */
    @RegisterConnection(value = "sqlite", defaultConnection = true)
    public GaarasonDataSource sqliteConnection(Environment env, ContainerBootstrap bootstrap) {
        com.alibaba.druid.pool.DruidDataSource druid = new com.alibaba.druid.pool.DruidDataSource();
        druid.setUrl(env.getProperty("spring.datasource.url", "jdbc:sqlite:data/ai-api-center.db"));
        druid.setDriverClassName(env.getProperty("spring.datasource.driver-class-name", "org.sqlite.JDBC"));
        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        if (username != null) {
            druid.setUsername(username);
        }
        if (password != null) {
            druid.setPassword(password);
        }
        // Druid 默认 testWhileIdle=true 但 validationQuery 为空，启动时会打 ERROR 日志。
        // 显式给一个校验查询，消掉噪音（SQLite / MySQL 都支持 SELECT 1）。
        druid.setValidationQuery("SELECT 1");
        return GaarasonDataSourceBuilder.build(druid, bootstrap);
    }
}
