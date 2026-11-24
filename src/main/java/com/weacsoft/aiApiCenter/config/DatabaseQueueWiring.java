package com.weacsoft.aiApiCenter.config;

import com.weacsoft.jaravel.vendor.core.queue.QueueDriver;
import com.weacsoft.jaravel.vendor.event.QueueDispatcher;
import com.weacsoft.jaravel.vendor.queue.database.DatabaseQueueDispatcher;
import com.weacsoft.jaravel.vendor.queue.database.DatabaseQueueDriver;
import com.weacsoft.jaravel.vendor.queue.database.DatabaseQueueWorker;
import com.weacsoft.jaravel.vendor.queue.database.QueueDatabaseProperties;
import com.weacsoft.jaravel.vendor.springboot.core.ContextBeanProvider;
import com.weacsoft.jaravel.vendor.springboot.queuedatabase.OnDatabaseQueueDriverCondition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;

/**
 * 消除 database 队列驱动装配中的按类型注入歧义。
 * <p>
 * vendor 的 {@code QueueDatabaseAutoConfiguration} 在 {@code driver=database} 时
 * 会同时注册两个 {@code QueueDriver} 类型的 Bean：真正的驱动
 * {@code databaseQueueDriver}，以及实现 {@code QueueDriver} 接口的门面
 * {@code QueueDriverHolder}。其自带的 {@code databaseQueueDispatcher} 与
 * {@code databaseQueueWorker} 以 {@code QueueDriver} 为形参做按类型注入，
 * 遇到两个候选且无 {@code @Primary}/{@code @Qualifier} 时抛出
 * {@code NoUniqueBeanDefinitionException}，导致 database 模式无法启动
 * （{@code sync} 模式下只有门面一个候选，故不受影响）。
 * <p>
 * 本配置仅在 {@code jaravel.queue.driver=database} 时生效，用具体类型
 * {@link DatabaseQueueDriver} 重新声明分发器与工作线程，使按类型注入无歧义；
 * 由于 vendor 的对应 Bean 带有 {@code @ConditionalOnMissingBean}，
 * 这里的声明会抑制它们，而不改变其余装配。
 */
@Configuration
@Conditional(OnDatabaseQueueDriverCondition.class)
public class DatabaseQueueWiring {

    @Bean
    public QueueDispatcher databaseQueueDispatcher(DatabaseQueueDriver driver,
                                                   ApplicationContext applicationContext) {
        return new DatabaseQueueDispatcher(driver, new ContextBeanProvider(applicationContext));
    }

    @Bean
    public DatabaseQueueWorker databaseQueueWorker(DatabaseQueueDriver driver,
                                                   QueueDatabaseProperties properties,
                                                   ApplicationContext applicationContext) {
        DatabaseQueueWorker worker = new DatabaseQueueWorker(
                driver,
                new ContextBeanProvider(applicationContext),
                properties.getQueues(),
                properties.getMaxAttempts(),
                properties.getRetryDelayMs(),
                properties.getPollIntervalMs(),
                properties.getWorkerThreads()
        );
        if (properties.isAutoStart()) {
            worker.start();
        }
        return worker;
    }
}
