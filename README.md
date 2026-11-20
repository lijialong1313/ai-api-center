# AI API Center — 多模式 vLLM 排队中间件

基于 [`jaravel-vendor`](https://github.com/weacsoft/jaravel-vendor) 的 OpenAI 兼容排队中间件。
多个 DeepSeek Harness（DSH）把请求发到本中间件，由它做**全局排队 + 会话串行 + 按 Key 加权公平**，
再单并发地转发给 vLLM，避免把 `max_num_seqs=1` 的后端打爆。

当前完成的是**基础基座**：可启动、可登录、三个管理界面可用、四种部署模式配置就绪。
准入调度层（平滑加权轮询 / SSE 转发 / 租约清理）按文档 §4 在下一阶段接入。

---

## 1. 快速启动

```powershell
# 1) 先安装 jaravel-vendor 到本地仓库（它尚未发布 0.1.3 到 Maven Central）
cd D:\0code\Java\jaravel-vendor
mvn clean install -DskipTests

# 2) 构建并运行本中间件
cd D:\0AI\ai-api-center
mvn clean package -DskipTests
java -jar target\ai-api-center.jar
```

启动后：

| 地址 | 说明 |
|---|---|
| `http://127.0.0.1:8090/healthz` | 健康探针 |
| `http://127.0.0.1:8090/admin` | 管理后台（需统一身份认证登录） |
| `http://127.0.0.1:8090/admin/api-keys` | API Key 管理 |
| `http://127.0.0.1:8090/admin/backends` | GPU 后端管理 |

首次启动会自动建表（`admin_users` / `api_keys` / `backend_pools`，由 migration 模块管理），
SQLite 文件默认落在 `data/ai-api-center.db`。

---

## 2. 管理员登录：统一身份认证

管理员**不保存本地口令**，登录完全走学校统一身份认证系统（广州城市理工学院智慧校园认证系统，
OAuth2.0 授权码模式，对接文档 v1.0.4）。

```
浏览器 → /admin/login
       → 302 到 auth.gcu.edu.cn/application/oauth/redirect?client_id=..&callbackUrl=..&redirect_uri=..
       → 用户在认证系统登录（拖验证码等由认证方处理，本系统不接触口令）
       → 302 回 /admin/sso/callback?code=..
       → 服务端用 code 换 access_token，再用 token 取用户信息
       → 按学工号 upsert admin_users，签发 JWT，写入 HttpOnly Cookie
       → 302 到 /admin
```

### 必须配置

```yaml
app:
  sso:
    base-url: https://auth.gcu.edu.cn
    client-id: ${SSO_CLIENT_ID}        # 认证系统分配的 key
    client-secret: ${SSO_CLIENT_SECRET} # 认证系统分配的 secret
    # 与认证系统登记的地址必须完全一致；留空则按当前请求自动推导
    redirect-uri: https://你的域名/admin/sso/callback
    callback-url: /admin
    admin-roles: 教师,教职工            # allRole 命中任一即为管理员
```

未配置 `client-id` / `client-secret` 时，登录页会明确提示「尚未配置」，不会白屏或静默失败。

### 认证 ≠ 授权

`/admin/**` 挂两道中间件：

1. `AdminAuthenticate` — 校验 JWT（支持 `Authorization: Bearer` 与 Cookie 两种传递方式），未登录 302 到登录页。
2. `RequireAdmin` — 校验 `role=admin` 且账号启用；能通过统一身份认证的师生很多，但只有命中
   `app.sso.admin-roles` 的账号才是管理员，其余返回 403 提示页。

关于 Cookie → Authorization 的适配：框架的 `JwtGuard` 只读 `Authorization` 头，
而浏览器访问后台时令牌在 Cookie 里。中间件会把 Cookie 补写成请求头，
**并且必须同时写到 `AuthContext.get()` 持有的那个 `Request` 上**——框架路由层
先 `buildFromServerRequest` 出一个 Request 放进 `AuthContext`，之后才把**另一个** Request
实例交给中间件链（两者不是同一对象）。只改中间件那个对象，守卫读到的仍是原始请求，
表现为「带 Authorization 头能过、只带 Cookie 一定 302」。

---

## 3. 四种部署模式

切换成本只有配置，业务代码不分叉（文档 §3.1 / §10.3）：

| 部署模式 | `jaravel.queue.driver` | 协调状态 | 必需模块 |
|---|---|---|---|
| 单机 · 无 Redis | `sync` | JVM 内存 | `starter` |
| 单机 · 有 Redis | `redis` | Redis | `starter` + `redis` |
| 多机 · 无 Redis | `database` | 共享库 `jobs` 表 | `starter` + `queue-database` |
| 多机 · 有 Redis | `redis` | Redis（原子 + 分布式锁） | `starter` + `redis` |

单机无 Redis（默认，零外部依赖）：

```yaml
jaravel:
  queue:
    driver: sync
```

有 Redis：

```yaml
jaravel:
  queue:
    driver: redis
  cache-redis:
    auto-register: true     # 显式启用 redis 缓存驱动
  redis:
    options:
      cluster: redis        # redis / sentinel / cluster
      prefix: "llm_gw_"
    connections:
      default:
        host: ${REDIS_HOST:127.0.0.1}
        port: ${REDIS_PORT:6379}
```

多机无 Redis：

```yaml
jaravel:
  queue:
    driver: database
```

生产换 MySQL 只需替换 `spring.datasource.*`，Model 与迁移无需改动。

---

## 4. 已实现的能力

| 能力 | 位置 | 说明 |
|---|---|---|
| 统一身份认证登录 | `SsoService` / `LoginController` | 授权码模式，服务端换 token 取身份 |
| 账号开通与同步 | `UserProvisionService` | 按学工号 upsert，每次登录刷新组织信息 |
| JWT 签发与黑名单 | `AuthConfig` + `jaravel.jwt.*` | 登出后 token 立即失效 |
| 认证 / 授权中间件 | `AdminAuthenticate` / `RequireAdmin` | Cookie 与 Bearer 双支持；认证≠授权 |
| API Key 管理 | `ApiKeyService` | 库中只存 SHA-256，明文仅创建时可见一次 |
| 权重即时生效 | `SchedulerConfigCache` | 改权重后清本地缓存 + jaravel Cache |
| GPU 后端管理 | `BackendPoolService` | 地址 / 容量 / 启停 / 健康检查 |
| 健康检查 | `BackendPoolService#probe` | 探 `/health`，404 回退 `/v1/models` |
| 三张业务表 | `database/migration/*` | `admin_users` / `api_keys` / `backend_pools` |

---

## 5. 目录结构

```
src/main/java/com/weacsoft/aiApiCenter/
├── AiApiCenterApplication.java
├── app
│   ├── http
│   │   ├── controller/    LoginController / AdminController
│   │   └── middleware/     AdminAuthenticate / RequireAdmin / TrimStrings / ConvertEmptyStringsToNull
│   ├── model/             AdminUser / ApiKey / BackendPool
│   └── service/           SsoService / UserProvisionService / ApiKeyService
│                          BackendPoolService / SchedulerConfigCache
├── config/                DatabaseConfig / AuthConfig / RouteConfig / SsoProperties
└── database/migration/    三个 Migration_2026_11_20_*
src/main/resources/
├── application.yml
└── templates/admin/       layout / dashboard / api-keys / backends / login / forbidden
```

---

## 6. 踩过的坑（都已修复并留注释）

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动即 `Unable to read meta-data for class ...RedisAutoConfiguration` | 该模块 `AutoConfiguration.imports` 带 UTF-8 BOM | 上游 0.1.3 已重构，BOM 文件不复存在；本地历史分支 `backup/bom-fix-20261120` 留有当时的修复 |
| `BeanDefinitionOverrideException: sessionStoreHolder` | `HttpSessionAutoConfiguration` 缺 `@ConditionalOnMissingBean` | 已提交并推送到 jaravel-vendor `afc5563` |
| `[not implemented by SQLite JDBC driver]` | Spring Boot 3.2.12 管理的 sqlite-jdbc 3.43.2.2 不支持 `getGeneratedKeys()` | `pom.xml` 覆盖为 3.47.2.0 |
| `Error parsing time stamp` | gaarason 把 `LocalDateTime` 写成 ISO 格式，SQLite 读不回来 | 时间字段统一用 `String` + `TimestampFill`（jaravel 既定约定） |
| 模板 `jblade 模块函数未注册: size` | Blade 没有 `size()` 函数 | 集合长度在控制器算好传入 |
| 模板 `jblade 模块函数未注册: getId` | Blade 用 PHP 风格 `->` 而非 `.` 调用方法 | 模板统一改 `$obj->method()` |

---

## 7. 测试

```powershell
mvn test
```

`AdminBaseIntegrationTest` 共 10 个用例，覆盖：未登录重定向、登录页渲染、健康探针、
JWT 访问三个页面、Cookie 传递令牌、非管理员 403、API Key 生命周期、GPU 后端生命周期、
文档 DES 加密测试向量、可调度 Key 过滤。

---

## 8. 下一步

按文档 §4 接入准入调度层：

1. `QueueDriver.push` 入队（payload 带 `sessionId` / `apiKey` / `leaseDeadline`）；
2. 调度循环：取候选 → 检查会话 in-flight → 检查后端槽位 → 平滑加权轮询 → 放行或延迟重入队；
3. `WebClient` 转发 vLLM，SSE 透传，`doFinally` 释放槽位；
4. 忙时 `503 + Retry-After`（启发式估算，封顶 120s）；
5. 租约清理（不能只查队头）。

客户端侧配置见 [客户端配置.md](客户端配置.md)。
