# ai-api-center

基于 [`jaravel-vendor`](https://github.com/weacsoft/jaravel-vendor) 的 OpenAI 兼容排队中间件。

多个 DeepSeek Harness（DSH）实例把请求发到本服务，由它统一做**会话并发控制（按 Key 配置上限，默认串行）+ 后端槽位控制 + 按 Key 加权分配份额**，
再转发给 vLLM 后端。面向 `max_num_seqs=1` 这类单并发后端，避免请求超出后端承载能力。

配套的客户端插件：[dsh-session-gateway](https://github.com/lijialong1313/dsh-session-gateway)。

---

## 1. 功能范围

| 能力 | 位置 |
|---|---|
| OpenAI 兼容端点 `/v1/**`（chat/completions、completions、embeddings、models） | `OpenAiGatewayController` |
| 准入判定：会话并发上限（按 Key，默认 1 即串行）、后端槽位、等级门槛、按权重分配份额；无会话请求按普通网关放行（满则 429） | `AdmissionScheduler` / `AdmissionState` / `WeightedFairnessGate` |
| 忙时响应 `503 + Retry-After` | `OpenAiGatewayController#busy` |
| 统一身份认证登录（OAuth2 授权码模式） | `SsoService` / `LoginController` |
| 账号开通与组织信息同步 | `UserProvisionService` |
| 认证与授权中间件 | `AdminAuthenticate` / `RequireAdmin` |
| API Key 管理（权重、等级、会话并发上限、启停、重新生成） | `ApiKeyService` |
| GPU 后端管理（地址、容量、等级门槛、启停、健康检查） | `BackendPoolService` |
| 管理员账号管理 | `AdminUserService` |
| 启动时确保指定工号的管理员账号存在 | `BootstrapAdminInitializer` |

设计细节见 [`docs/design.md`](docs/design.md)。

---

## 2. 快速开始

### 2.1 前置条件

- JDK 17 或更高版本；
- Maven 3.9；
- 依赖 `jaravel-vendor` 0.1.3（坐标 `io.github.lijialong1313`），由 Maven 中央仓库自动解析，无需额外仓库配置与本地构建。

### 2.2 构建与运行

```powershell
cd ai-api-center
mvn clean package -DskipTests
java -jar target/ai-api-center.jar
```

首次启动自动建表（`admin_users` / `api_keys` / `backend_pools`），SQLite 数据文件默认位于
`data/ai-api-center.db`。

| 地址 | 说明 |
|---|---|
| `http://127.0.0.1:8090/healthz` | 健康探针 |
| `http://127.0.0.1:8090/admin` | 管理后台 |
| `http://127.0.0.1:8090/v1/chat/completions` | OpenAI 兼容端点 |

### 2.3 本地配置

仓库内只保留占位符。真实凭证写入 `application-local.yml`（已在 `.gitignore` 中），
模板见 [`application-local.yml.example`](application-local.yml.example)。

```yaml
app:
  sso:
    client-id: "<认证系统分配的 client_id>"
    client-secret: "<认证系统分配的 client_secret>"
    # 可选：必须与认证系统登记的回调地址完全一致
    # redirect-uri: "https://<host>/admin/sso/callback"
```

配置项也可以通过环境变量提供，优先级为：环境变量 > `application-local.yml` > `application.yml`。

### 2.4 必需的环境变量

| 变量 | 说明 |
|---|---|
| `JARAVEL_KEY` | 应用密钥，派生 JWT 验签与 Cookie 加密。未设置时框架改用进程级随机密钥，重启后已签发的令牌全部失效 |
| `JWT_SECRET` | JWT 签名密钥，生产环境必须更换默认值 |
| `SSO_CLIENT_ID` / `SSO_CLIENT_SECRET` | 统一身份认证凭证 |
| `BOOTSTRAP_ADMIN_STAFF_NO` | 启动时确保存在的管理员工号，默认 `2019095`，留空关闭该机制 |
| `ADMIN_AUTH_ENABLED` | 管理后台认证开关，默认 `true`；置为 `false` 时跳过认证，仅供本机联调 |

---

## 3. 管理员登录

登录完全走统一身份认证系统，本服务不保存本地口令。

```text
浏览器 → GET /admin/login
       → 302 到认证系统授权页
       → 用户在认证系统完成登录
       → 302 回 GET /admin/sso/callback?code=...
       → 服务端用 code 换取 access_token，再用 token 取用户信息
       → 按学工号开通或更新 admin_users 记录
       → 签发 JWT，写入 HttpOnly Cookie
       → 302 到 /admin
```

授权与换取令牌使用同一个 `redirect_uri`：登录入口把解析出的回调地址存入会话，
回调时优先取会话中的值，保证两端一致。

### 认证与授权

`/admin/**` 挂载两道中间件：

1. `AdminAuthenticate`：校验 JWT，同时支持 `Authorization: Bearer` 与 Cookie 两种传递方式，未登录时重定向到登录页；
2. `RequireAdmin`：校验 `role=admin` 且账号处于启用状态。

认证系统的 `allRole` 命中 `app.sso.admin-roles`（默认 `教师,教职工`）时，本地记录的角色为 `admin`，
否则为 `user`。角色为 `user` 的记录当前不承担业务功能，用于保存已登录用户的组织信息，
使管理员可以在后台调整其角色与启用状态；该记录不可省略，否则无法在登录前判定是否授予管理员权限。

Cookie 与 Bearer 的适配需要同时写入两个请求对象：框架的守卫只读 `Authorization` 头，
而路由层与中间件链拿到的并非同一个 `Request` 实例，只改其中一个会导致携带 Cookie 的请求始终被判定为未登录。

---

## 4. 准入判定

每次请求到达 `/v1/**` 时，服务端按是否携带 `x-session-id` 请求头走两条准入路径：

### 4.1 携带 `x-session-id`：会话并发控制 + 忙时排队

面向 DSH 等会话型客户端。判定顺序如下，任一不满足即拒绝，
判定不通过时返回 `503 + Retry-After`，客户端按该间隔重试，每次重试重新判定。
服务端不维护常驻队列，也不维护排队位置。

| 顺序 | 判定 | 拒绝结果 |
|---|---|---|
| 0 | Key 已停用或权重为 0 | 终态，返回 403，不附带 `Retry-After` |
| 1 | 该会话在途数已达该 Key 的会话并发上限（默认 1，即串行） | 可重试 |
| 2 | 无空闲且等级允许的后端 | 可重试；等级不足时给出准确原因 |
| 3 | 本轮未轮到该 Key（权重配额） | 可重试，给出更短的重试间隔 |
| 4 | 占用会话名额与槽位 | 竞争失败则归还已记账的份额并重试 |

第 0 项必须排在可重试判断之前：已停用的 Key 若返回 503，客户端会持续重试且始终无法成功。

第 1 项的并发上限按 Key 在 `api_keys.concurrency` 配置，默认 1（严格串行）。
DSH 等客户端在同一会话内会并发发起多个请求（主请求与后台自动起标题等），
默认串行下后发者会收到 503 排队；把上限调高到 2 或 3 后这些请求可以并行。

权重配额把权重理解为长期服务比例：设已服务总数为 `t`、本 Key 已服务 `s`、本 Key 权重 `w`、权重之和 `W`，
则 `ideal = (t + 1) * w / W`；`s < ideal` 时放行，否则让路。统计窗口为 60 秒。

后端选择在等级允许且有空闲槽位的后端中选取在途请求数最少的一个。

### 4.2 不携带 `x-session-id`：按普通网关处理

面向 curl / Python 等无会话概念的客户端（如拉取 `/v1/models`）。
要求最宽松：**有空闲槽位立即放行**，请求期间占用该槽位、结束即释放，
**不登记会话、不登记会话锁、不参与权重配额**；请求也不会向下游透传 `x-session-id`。

拒绝时为**快速失败，不引导排队**：

| 判定 | 拒绝结果 |
|---|---|
| Key 已停用或权重为 0 | 终态，返回 403 |
| 无空闲且等级允许的后端 / 槽位竞争失败 | 返回 429，附带 `X-Queue-Reason`，**不附带 `Retry-After`** |

即「机器没空就直接 429」，客户端自行决定退避节奏；这与普通 OpenAI 网关
在过载时的行为一致，只是本服务额外给出了诊断用的 `X-Queue-Reason`。

### 忙时 / 拒绝响应

携带会话的请求忙时：

```http
HTTP/1.1 503 Service Unavailable
Retry-After: <秒数>
X-Queue-Reason: <session_busy|backend_full|weight_round_wait|slot_race|queued>
```

不携带会话的请求槽位已满：

```http
HTTP/1.1 429 Too Many Requests
X-Queue-Reason: <backend_full|level_blocked>
Content-Type: application/json;charset=utf-8

{"error":{"message":"所有后端繁忙，请稍后重试","type":"rate_limited","code":"backend_full"}}
```

`Retry-After`（仅 503）的估算：常规情况按在途请求数给出保守切片（`2 + 在途数`），
本轮未轮到该 Key 时按 `ceil(2 * 权重和 / 本 Key 权重)` 给出更短的间隔，两者均夹在 1 秒至 120 秒之间。

`X-Queue-Reason` 只使用 ASCII 标识。HTTP 头只能承载 ISO-8859-1 字符，
中文内容会导致 503 变为 500，客户端不再按 `Retry-After` 重试。

---

## 5. 转发行为

- 请求体按原始字节转发，不重新序列化；
- 转发使用 HTTP/1.1。JDK 客户端默认对明文 http 尝试 h2c 升级，vLLM 前置的 uvicorn/starlette
  对该升级支持不完整，表现为上游读不到请求体并返回 400；
- 上游鉴权使用后端自身配置的 Key，头名可配，默认 `Authorization`，值为 `Bearer` 形式；
- 流式响应逐块透传，结束判定以协议终结标记 `data: [DONE]` 为准，不依赖读取到流末尾；
- 槽位在转发结束后释放，包含收到响应头、转发失败与客户端断开三种路径。

---

## 6. 部署模式

框架侧的队列驱动与缓存驱动按四种组合装配，业务代码不分叉：

| 部署模式 | `jaravel.queue.driver` | 协调状态存放 | 必需模块 |
|---|---|---|---|
| 单机 · 无 Redis | `sync`（默认）或 `database` | JVM 内存 / `jobs` 表 | `starter`（`database` 另需 `queue-database`） |
| 单机 · 有 Redis | `redis` | Redis | `starter` + `redis` |
| 多机 · 无 Redis | `database` | 共享数据库 `jobs` 表 | `starter` + `queue-database` |
| 多机 · 有 Redis | `redis` | Redis | `starter` + `redis` |

`sync` 为进程内存队列，重启即清空；`database` 把任务写入 `jobs` 表，重启后仍可消费，
两种驱动均不依赖 Redis，在单机部署下可自由切换。`jobs` / `failed_jobs` 两张表由应用
自带的迁移在启动时统一创建，无需手工执行 DDL。

数据库由 `spring.datasource.*` 决定，默认 SQLite，切换 MySQL 时模型与迁移无需改动。

准入状态（会话占用与后端槽位）目前保存在进程内存中，判定临界区为进程内互斥锁，
因此当前实现适用于单实例部署。多实例部署需要把该状态迁移到共享存储。

---

## 7. 目录结构

```text
.
├── src/main/java/com/weacsoft/aiApiCenter/
│   ├── app
│   │   ├── http/controller/    LoginController / AdminController / OpenAiGatewayController / CurrentAdmin
│   │   ├── http/middleware/     AdminAuthenticate / RequireAdmin / TrimStrings / ConvertEmptyStringsToNull
│   │   ├── model/               AdminUser / ApiKey / BackendPool
│   │   ├── scheduler/           AdmissionScheduler / AdmissionState / WeightedFairnessGate
│   │   └── service/             SsoService / UserProvisionService / AdminUserService
│   │                            ApiKeyService / BackendPoolService / SchedulerConfigCache
│   ├── config/                  AdminAccessProperties / SsoProperties / AuthConfig / DatabaseConfig
│   │                            RouteConfig / StartupSecurityCheck / BootstrapAdminInitializer / LocalDevAdminShim
│   └── database/migration/      8 个迁移类
├── src/main/resources/
│   ├── application.yml
│   └── templates/admin/         layout / dashboard / api-keys / backends / users / login / forbidden
├── src/test/                    10 个测试类
├── tools/                       fake-vllm.py / queue-probe.py / seed-e2e.py
└── docs/design.md               设计文档
```

---

## 8. 本地联调工具

`tools/` 下提供三个脚本，用于在无 GPU 的环境下验证整条链路：

```powershell
# 1) 启动 OpenAI 兼容桩服务（默认 59998 端口）
python tools/fake-vllm.py

# 2) 向 SQLite 库写入联调用 Key 与后端记录
python tools/seed-e2e.py

# 3) 对运行中的中间件做并发实验：同会话串行、容量限制、按提示重试
python tools/queue-probe.py
```

---

## 9. 测试

```powershell
mvn test
```

覆盖范围包括：认证与授权链路、API Key 与后端的完整生命周期（含会话并发上限）、
准入判定与加权分配（含并发上限下的同会话并行与超限拒绝）、
端到端转发（含 SSE 流式响应）、启动管理员的创建与不覆盖既有账号的行为。

测试 profile 使用独立 SQLite 库（`data/test-ai-api-center.db`），并把迁移源覆写为
`DIRECTORY_CLASSES`（扫 `target/classes`），原因见 [`docs/design.md`](docs/design.md) §6。

---

## 10. 许可

MIT，见 [LICENSE](LICENSE)。
