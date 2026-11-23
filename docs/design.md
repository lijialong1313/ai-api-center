# 服务端设计文档：基于 jaravel-vendor 的多模式 vLLM 排队中间件

## 1. 背景与约束

### 1.1 硬件与后端

- 后端 vLLM 运行在 2×RTX3090 上，`max_num_seqs=1`，同一时刻只能处理一个请求。
- 后端可能不止一个，**大多数后端容量为 1**，个别后端容量可能为 2 或更高。
- 多个 DeepSeek Harness（DSH）分布在不同设备，通过 OpenAI 兼容端点访问。

### 1.2 技术约束（已确认）

- 服务端使用 Java 开发，技术底座为 **`jaravel-vendor`**（https://github.com/weacsoft/jaravel-vendor）。
- **同时支持单机 / 多机，有 Redis / 无 Redis 四种部署组合**。
- 不强制绑定 Redis；Redis 作为可选协调层。
- 需要三个管理界面：
  1. API Key 管理（增删改查、权重配置、启用/禁用）。
  2. GPU 后端管理（地址、容量、启用/禁用、健康检查）。
  3. 管理员登录（认证与授权）。

### 1.3 核心调度语义

- 全局排队，避免请求直接超出 vLLM 的承载能力。
- 同一 `session_id` 的多轮 Agent 请求严格串行，不与其他会话交错。
- 按 API Key 加权公平：高权重获得更高服务比例，低权重同样有保底份额。
- 忙时返回 `503 + Retry-After`，支持客户端短轮询重试。
- 流式响应期间占用槽位，转发结束后释放。

排队由客户端重试行为承载：服务端不维护常驻队列与请求排队位置，
每次重试都重新走一遍准入判断。详见 §4。

---

## 2. jaravel-vendor 模块选型

根据 `jaravel-vendor` 的实际模块能力，选用以下模块：

| 模块 | 用途 |
|---|---|
| `starter` | 聚合基础模块，提供应用启动骨架 |
| `queue-database` | 队列抽象，原生支持 `sync` / `database` / `redis` 三种驱动 |
| `redis` | Redis 驱动、`RedisLockProviderImpl`、`redis-cache` |
| `cache` | 统一缓存抽象，支撑会话 in-flight 标记、本地缓存 |
| `auth` | 认证守卫、`Authenticate` 中间件、`Auth` 门面 |
| `jwt` | JWT 驱动，作为 `auth` 的 guard driver |
| `jblade` | Blade 模板引擎，渲染管理后台页面 |
| `wire`（可选） | Livewire 风格响应式 UI，用于表单实时校验 |
| `migration` | 跨库迁移，管理 `admin_users` / `api_keys` / `backend_pools` 等业务表 |

**关键模块说明：**

- `queue-database` 的 `QueueDriver` 接口提供 `push`、`pop`、`release`、`fail`、`size` 等标准操作，`sync`、`database`、`redis` 三种驱动共享同一契约。
- Redis 驱动**严格按需装配**，未引入 `redis` 模块时不会隐式回退到 database。部署意图显式。
- `core` 模块定义 `LockProvider` 契约，`redis` 模块提供 `RedisLockProviderImpl` 实现。
- `migration` 支持 MySQL / SQLite / H2 / SQL Server / PostgreSQL / Oracle，建表统一走 `artisan migrate`。

---

## 3. 四种部署模式的统一架构

### 3.1 部署矩阵

| 部署模式 | 队列驱动 | 协调状态存放 | 必需模块 | 适用场景 |
|---|---|---|---|---|
| **单机 · 无 Redis** | `sync` | JVM 内存 | `starter` | 开发、单实例小规模 |
| **单机 · 有 Redis** | `redis` | Redis（可跨重启持久化） | `starter` + `redis` | 单实例，队列状态不丢失 |
| **多机 · 无 Redis** | `database` | 共享数据库的 `jobs` 表 | `starter` + `queue-database` | 多实例，已有数据库，不引入 Redis |
| **多机 · 有 Redis** | `redis` | Redis（原子操作、分布式锁） | `starter` + `redis` | 多实例，低延迟与强原子性 |

切换成本：业务代码无需分叉，只改 `application.yml` 中的 `jaravel.queue.driver` 及对应连接配置。

### 3.2 整体架构

```text
多个 DSH 设备
    │  OpenAI Chat Completions
    │  请求头：x-session-id / Authorization
    ▼
┌──────────────────────────────────────────────────┐
│  Java 中间件（jaravel-vendor）                     │
│                                                    │
│  ┌────────────────────────────────────────────┐   │
│  │ 准入调度层                                  │   │
│  │  - AdmissionScheduler（准入判定）           │   │
│  │  - AdmissionState（会话与槽位状态）         │   │
│  │  - WeightedFairnessGate（按权重分配份额）   │   │
│  │  - SchedulerConfigCache（权重配置缓存）     │   │
│  └────────────────────────────────────────────┘   │
│                                                    │
│  ┌────────────────────────────────────────────┐   │
│  │ 管理后台（/admin/**）                       │   │
│  │  - 登录（统一身份认证 + JWT）               │   │
│  │  - API Key 管理（jblade）                   │   │
│  │  - GPU 后端管理（jblade）                   │   │
│  │  - 管理员账号管理（jblade）                 │   │
│  └────────────────────────────────────────────┘   │
│                                                    │
│  ┌────────────────────────────────────────────┐   │
│  │ 数据库（migration 管理）                    │   │
│  │  - admin_users / api_keys / backend_pools   │   │
│  │  - jobs（database 模式）                    │   │
│  └────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────┘
    │  同一时刻每后端 in-flight ≤ capacity
    ▼
vLLM 后端（容量通常为 1）
```

---

## 4. 准入调度层设计

### 4.1 职责划分

| 组件 | 职责 | 实现位置 |
|---|---|---|
| 准入判定 | 判断本次请求现在能否进入后端，进入哪一个 | `AdmissionScheduler` |
| 会话与槽位状态 | 维护在途会话、各后端 in-flight 计数与容量 | `AdmissionState` |
| 按权重分配份额 | 决定本轮是否轮到该 Key | `WeightedFairnessGate` |
| 权重配置缓存 | 缓存可用 Key 的权重，后台改动后立即失效 | `SchedulerConfigCache` |
| 临界区互斥 | 保证「检查 → 放行」不被并发交错 | `AdmissionScheduler` 内的 `decisionLock` |
| 释放 | 转发结束后清除会话与槽位占用 | `AdmissionScheduler#release` |

### 4.2 调度模型：请求驱动

排队位置由客户端的重试行为承载，服务端不维护常驻队列，也不维护请求的排队顺序：

1. 请求到达 `/v1/**`，服务端执行一次准入判定；
2. 判定不通过时返回 `503 + Retry-After`，客户端按该间隔重试；
3. 每次重试都重新走一遍准入判定，先到先得。

该模型的性质：

- 不存在「调度器已取出请求但客户端已放弃」的状态；
- 准入判定是一次原子操作，四种部署模式行为一致；
- 排队顺序不保证严格 FIFO，公平性由权重配额保证（见 §4.3）。

### 4.3 准入判定顺序

`AdmissionScheduler#decide(requestId, sessionId, keyHash, keyLevel)` 按以下顺序判定，
任一不满足即拒绝：

| 顺序 | 判定 | 拒绝结果 |
|---|---|---|
| 0 | Key 已停用或权重为 0 | 终态，`retryAfter = 0`，返回 403 |
| 1 | 同一会话已有在途请求 | 可重试，`Retry-After` 按在途数估算 |
| 2 | 无空闲且等级允许的后端 | 可重试；等级不足时给出准确原因 |
| 3 | 本轮未轮到该 Key（公平配额） | 可重试，给出更短的重试间隔 |
| 4 | 占会话与槽位 | 竞争失败则归还公平额度并重试 |

第 0 项必须排在可重试判断之前：已停用的 Key 若返回 503，客户端会按提示持续重试且始终无法成功。

第 4 项竞争失败时调用 `WeightedFairnessGate#rollback` 归还已记账的服务额度，
否则该 Key 消耗了额度却没有获得服务，长期服务比例会低于其权重。

后端选择规则：在等级允许且有空闲槽位的后端中，选取在途请求数最少的一个。
各后端容量均为 1 时，该规则等价于轮转。

### 4.4 按权重分配份额

`WeightedFairnessGate` 采用配额制，将权重理解为长期服务比例：

```text
已服务总数 t、本 Key 已服务 s、本 Key 权重 w、权重之和 W

ideal = (t + 1) * w / W
s < ideal  → 放行（尚未达到应得份额）
否则        → 让路
```

- 理想值取 `t + 1`，把本次服务计入统计，否则每个 Key 都差一步、开头全部被拒。
- 只要权重大于 0 的 Key 持续发请求，`t` 的增长会使 `ideal` 追上并超过 `s`，
  因此不存在长期得不到服务的情况。
- 统计窗口为 60 秒。窗口到期后计数清零，使权重调整后的新比例不被旧数据长期延续，
  同时避免计数无限增长导致的浮点精度下降。

### 4.5 忙时响应

无法立即准入时返回：

```http
HTTP/1.1 503 Service Unavailable
Retry-After: <秒数>
X-Queue-Reason: <session_busy|backend_full|weight_round_wait|slot_race|queued>
```

- `Retry-After` 的估算：常规情况按在途请求数给出保守切片（`2 + 在途数`），
  本轮未轮到该 Key 时按 `ceil(2 * 权重和 / 本 Key 权重)` 给出更短的间隔；
  两者均夹在 1 秒至 120 秒之间。
- `X-Queue-Reason` 只使用 ASCII 标识。HTTP 头只能承载 ISO-8859-1 字符，
  中文原因会让容器抛出编码异常，导致 503 变为 500，客户端不再按 `Retry-After` 重试。
- 终态拒绝（Key 已停用）返回 403，不附带 `Retry-After`，不引导客户端重试。

---

## 5. 管理后台设计

管理界面共享 `/admin/**` 路由组，统一挂载 `AdminAuthenticate` 与 `RequireAdmin` 两道中间件。

### 5.1 管理员登录

登录完全走学校统一身份认证系统（OAuth2.0 授权码模式），本系统不保存本地口令，
也不接触用户口令。涉及 `auth` 与 `jwt` 两个模块：

- `auth` 提供 `Auth.login(user, "admin")` 门面与守卫机制；
- `jwt` 作为 `admin` 守卫的 driver，负责签发与验签，并支持黑名单。

流程：

```text
浏览器 → GET /admin/login
       → 302 到认证系统授权页（携带 client_id 与 redirect_uri）
       → 用户在认证系统完成登录
       → 302 回 GET /admin/sso/callback?code=...
       → 服务端用 code 换取 access_token，再用 token 取用户信息
       → 按学工号开通或更新本地 admin_users 记录
       → Auth.login(user, "admin") 签发 JWT，写入 HttpOnly Cookie
       → 302 到 /admin
```

授权与换取令牌使用同一个 `redirect_uri`：登录入口把解析出的回调地址存入会话，
回调时优先取会话中的值，保证两端一致。

管理员判定：认证系统返回的 `allRole` 命中 `app.sso.admin-roles` 中的任一角色时，
本地记录的 `role` 置为 `admin`，否则为 `user`。非管理员可以完成登录，
访问后台时由 `RequireAdmin` 返回 403 页面。

角色为 `user` 的记录是授权账本：记录已登录过的师生及其组织信息，
使管理员可以在后台调整其角色与启用状态。这类记录当前不承担业务功能，
但不能省略，否则无法在登录前判定是否应当授予管理员权限。

JWT 黑名单启用，管理员登出后已签发的令牌立即失效。

Cookie 与 Bearer 两种传递方式均被支持：框架的守卫只读 `Authorization` 头，
而浏览器访问后台时令牌位于 Cookie，中间件需要把 Cookie 补写为请求头，
并同时写入框架 `AuthContext` 持有的请求对象与中间件链上的请求对象
（两者不是同一实例）。

管理 API 与 DSH 流量分离：管理后台位于 `/admin/**`，模型调用位于 `/v1/**`。

### 5.2 API Key 管理界面

**路由：**

```text
GET  /admin/api-keys
POST /admin/api-keys
POST /admin/api-keys/update
POST /admin/api-keys/regenerate
POST /admin/api-keys/toggle
POST /admin/api-keys/delete
```

**数据模型 `api_keys`：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `key_hash` | varchar | SHA-256 十六进制，鉴权按该列查询 |
| `key_value` | varchar | Key 明文，供后台查看与复制 |
| `key_prefix` | varchar | 明文前 11 位，便于识别 |
| `label` | varchar | 可读名称 |
| `weight` | int | 相对权重，默认 1 |
| `level` | int | Key 等级，数值越大等级越高 |
| `enabled` | boolean | 是否启用 |
| `last_used_at` | timestamp | 最近使用时间 |
| `created_at` | timestamp | 创建时间 |
| `updated_at` | timestamp | 更新时间 |

**页面功能（jblade 渲染）：**

- 列表展示全部 Key 及其名称、权重、等级与启用状态，明文默认打码；
- 新增 Key（生成 `sk-ac-` 前缀的随机值，页面提供复制）；
- 重新生成 Key 明文：保留名称、权重、等级与启用状态，旧值立即失效；
- 修改权重、等级与启用状态；
- 删除 Key。

鉴权按 `key_hash` 查询，同时保留 `key_value` 明文列，使管理员可以随时查看与复制 Key。
日志不输出该字段。

**权重即时生效**：修改后调用 `SchedulerConfigCache#invalidateWeights`，
清空进程内权重表并刷新框架缓存，下一次准入判定即读取到新权重。
权重表另有一份短 TTL 本地缓存（10 秒），用于多实例场景下感知其他实例的改动。

### 5.3 GPU 后端管理界面

**路由：**

```text
GET  /admin/backends
POST /admin/backends
POST /admin/backends/update
POST /admin/backends/toggle
POST /admin/backends/delete
POST /admin/backends/health-check
```

**数据模型 `backend_pools`：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `backend_id` | varchar | 唯一标识 |
| `name` | varchar | 可读名称 |
| `base_url` | varchar | vLLM OpenAI 兼容端点 |
| `capacity` | int | 并发容量，默认 1 |
| `enabled` | boolean | 是否参与调度 |
| `health_path` | varchar | 健康检查路径，默认 `/health` |
| `api_key` | varchar | 上游 API Key，为空表示上游不鉴权 |
| `auth_header` | varchar | 上游鉴权头名，默认 `Authorization` |
| `default_model` | varchar | 默认模型名 |
| `min_level` | int | 最低 Key 等级，0 表示不限制 |
| `last_health_status` | varchar | `UNKNOWN` / `HEALTHY` / `UNHEALTHY` |
| `last_checked_at` | timestamp | 最近健康检查时间 |

**页面功能：**

- 列出全部后端及其容量、等级门槛与健康状态；
- 新增与编辑后端地址、容量、等级门槛、上游鉴权信息；
- 启用与禁用后端（禁用后调度器不再向其放行）；
- 手动触发健康检查。

**健康检查**：向 `base_url + health_path` 发 GET 请求；返回 404 时回退探测 `/v1/models`，
部分 vLLM 部署只开放后者。任一探测返回 2xx 即视为健康，
结果写回 `last_health_status` 与 `last_checked_at`。

**上游鉴权**：客户端提交的是本系统颁发的 Key，vLLM 不予识别，
转发时使用该后端自身配置的 Key，头名可配，值为 `Bearer` 形式。

**容量变更处理**：容量对应槽位计数。修改后调度器在下一轮判定时按新容量重建槽位状态，
回源间隔为 5 秒。

### 5.4 管理员账号管理

**路由：**

```text
GET  /admin/users
POST /admin/users
POST /admin/users/update
POST /admin/users/toggle-role
POST /admin/users/toggle-enabled
POST /admin/users/delete
```

**数据模型 `admin_users`：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `staff_no` | varchar | 学工号，唯一 |
| `name` | varchar | 姓名 |
| `college` | varchar | 所属单位 |
| `role_name` | varchar | 认证系统返回的主角色名 |
| `all_roles` | varchar | 认证系统返回的全部角色 |
| `role` | varchar | 本地授权角色：`admin` 或 `user` |
| `enabled` | boolean | 账号是否启用 |
| `last_login_at` | timestamp | 最近登录时间 |

**启动时确保存在的管理员工号**：配置项 `app.admin.bootstrap-admin-staff-no`。
库中不存在该工号时以 `admin` 角色创建，已存在时不修改任何字段，
因此在后台降权或停用之后重启不会恢复原状。

**保护规则**：系统内最后一个可用管理员不可被删除或停用。

---

## 6. 数据库表清单

业务表由 `migration` 模块管理，应用启动时自动执行迁移。

| 表名 | 用途 | 是否必需 |
|---|---|---|
| `admin_users` | 管理员账号与授权记录 | 是 |
| `api_keys` | Key、权重、等级、启用状态 | 是 |
| `backend_pools` | 后端地址、容量、等级门槛、启用状态 | 是 |
| `migrations` | 迁移执行记录 | 是 |

迁移文件位于 `src/main/java/com/weacsoft/aiApiCenter/database/migration/`，共 6 个：
三个建表迁移与三个后续字段追加迁移。

三张业务表均启用软删除，模型层的查询自动附加 `deleted_at IS NULL` 条件。

---

## 7. 配置示例

### 7.1 `application.yml`

```yaml
jaravel:
  queue:
    driver: redis          # 四选一：sync / database / redis
    database:
      table: jobs
    redis:
      connection: default

  redis:
    options:
      cluster: redis
      prefix: "llm_gw_"
    connections:
      default:
        host: ${REDIS_HOST:127.0.0.1}
        port: ${REDIS_PORT:6379}
        password: ${REDIS_PASSWORD:}

  auth:
    default-guard: admin
    guards:
      admin:
        driver: jwt
        provider: admins

  jwt:
    secret: ${JWT_SECRET}
    ttl: 86400000
    blacklist-enabled: true
```

### 7.2 四种模式切换示例

**单机无 Redis：**

```yaml
jaravel:
  queue:
    driver: sync
```

**单机有 Redis：**

```yaml
jaravel:
  queue:
    driver: redis
  redis:
    connections:
      default:
        host: 127.0.0.1
        port: 6379
```

**多机无 Redis（共享数据库）：**

```yaml
jaravel:
  queue:
    driver: database
```

**多机有 Redis：**

```yaml
jaravel:
  queue:
    driver: redis
  redis:
    options:
      cluster: redis   # 或 sentinel / cluster
    connections:
      default:
        host: ${REDIS_HOST}
        port: 6379
```

Redis 连接本身支持 standalone / sentinel / cluster，通过 `jaravel.redis.options.cluster` 切换。

---

## 8. 多机部署协调要点

### 8.1 有 Redis 模式

- 队列：每个 Key 一个 `RBlockingQueue`，`QueueDriver.pop` 原子取出。
- 会话 in-flight：`redis-cache` 的 `putIfAbsent` 保证原子标记。
- 槽位：`RSemaphore(backendId, capacity)` 原子获取与释放。
- 临界区：`RedisLockProviderImpl` 提供分布式锁，保护「检查 → 放行」临界区。
- 配置同步：管理界面修改 `api_keys` / `backend_pools` 后，通过 `RTopic` 发布变更事件，各实例刷新本地缓存。

### 8.2 无 Redis 多机模式（database）

- 队列：`queue-database` 的 `DatabaseQueueDriver`，`pop` 通过数据库行锁保证多实例竞争安全。
- 会话 in-flight：用 `inflight_sessions` 表，通过 `INSERT ... ON CONFLICT DO NOTHING` 或 `SELECT ... FOR UPDATE` 保证原子性。
- 槽位：用 `backend_pools` 表的可用计数字段配合 `SELECT ... FOR UPDATE`，或使用数据库驱动的原子计数器。
- 配置同步：管理界面直接写库，各实例通过短 TTL 本地缓存获取最新配置。

### 8.3 单机模式

单机模式下无需跨实例协调，`sync` 驱动即内存队列管理器，缓存默认内存实现。

### 8.4 当前实现的适用范围

准入状态（会话占用与后端槽位）目前保存在进程内存中，判定临界区为进程内互斥锁。
因此当前的实现适用于**单实例部署**；多实例部署需要将会话与槽位状态迁移到共享存储
（Redis 原子操作或数据库行锁），并把临界区改为分布式锁。

框架侧的队列驱动与缓存驱动已按 §3.1 的四种组合完成装配隔离，
切换时业务代码无需分叉；准入状态的共享化属于部署多实例时的后续工作。

---

## 9. 关键不变量与注意事项

### 9.1 必须保持的不变量

1. **同一 `sessionId` 在任意时刻最多只有一个请求处于「转发到 vLLM」状态。**
2. **每个后端在任意时刻的 in-flight 请求数不超过其容量。**
3. **高权重 Key 获得更高服务比例，低权重 Key 有保底执行机会。**
4. **流式响应期间槽位被占用，转发结束后才释放。**
5. **请求体的转发使用原始字节**，不做重新序列化。

### 9.2 注意事项

1. **排队状态由客户端重试承载，不要用客户端本地队列替代服务端判定。** 多设备场景下客户端队列无法协调。
2. **不要返回 429 表示「排队中」。** 应返回 `503 + Retry-After`。
3. **准入判定必须串行化或通过原子操作保护。** 当前实现使用进程内互斥锁。
4. **终态拒绝必须排在可重试判断之前。** 已停用的 Key 若返回 503，客户端会持续重试且始终无法成功。
5. **槽位释放在转发结束后执行。** 不能在收到响应头时释放。
6. **所有生产流量必须携带 `x-session-id`。** 未携带的请求直接拒绝。
7. **Redis 驱动严格按需装配。** 未引入 `redis` 模块时不会静默降级到 database，部署意图必须显式。
8. **管理 API 与 DSH 流量分离。** 管理后台位于 `/admin/**`，模型调用位于 `/v1/**`。
9. **JWT 黑名单启用。** 管理员登出后令牌立即失效。
10. **转发使用 HTTP/1.1。** JDK 客户端默认对明文 http 尝试 h2c 升级，
    vLLM 前置的 uvicorn/starlette 对该升级支持不完整，表现为上游读不到请求体并返回 400。
11. **SSE 的结束判定以协议终结标记为准。** HTTP/1.1 keep-alive 下上游发送
    `data: [DONE]` 后连接可能被复用而不返回 EOF，仅依赖 EOF 会使转发线程阻塞至超时，
    期间会话与槽位持续占用。

---

## 10. 与 jaravel-vendor 的适配说明

### 10.1 可直接复用的模块能力

| 需求 | jaravel-vendor 模块 | 说明 |
|---|---|---|
| 多驱动队列 | `queue-database` | `sync` / `database` / `redis` 三驱动，按需装配 |
| 分布式锁 | `redis` 模块的 `RedisLockProviderImpl` | 实现 `core` 的 `LockProvider` 契约 |
| 缓存抽象 | `cache` 模块 | 支撑权重缓存与后续的跨实例状态同步 |
| 认证 | `auth` 模块 | 多守卫、`Authenticate` 中间件、`Auth` 门面 |
| JWT | `jwt` 模块 | 作为 `auth` 的 guard driver，支持黑名单 |
| 管理界面渲染 | `jblade` | Blade 模板，渲染列表、表单、弹窗 |
| 跨库迁移 | `migration` | MySQL / SQLite / H2 / SQL Server / PostgreSQL / Oracle |

### 10.2 需要自行实现的部分

- **准入判定与公平配额**：`AdmissionScheduler`、`AdmissionState` 与 `WeightedFairnessGate` 由应用层实现，
  框架不提供加权公平语义。
- **业务表与模型**：`admin_users`、`api_keys`、`backend_pools` 的迁移与模型由应用层定义。
- **管理页面的 Blade 模板**：`jblade` 提供模板引擎，页面内容由应用层编写。
- **健康检查逻辑**：调用 vLLM 的 `/health` 或 `/v1/models`，结果写入 `backend_pools.last_health_status`。
- **统一身份认证对接**：认证系统的授权、换取令牌与用户信息接口由应用层封装。

### 10.3 切换成本总结

业务代码中需要感知部署模式的地方限于配置：

- 单机无 Redis → `queue.driver=sync`，缓存使用内存实现；
- 单机有 Redis → `queue.driver=redis`，引入 `redis` 模块；
- 多机无 Redis → `queue.driver=database`，依赖 `jobs` 表；
- 多机有 Redis → `queue.driver=redis`，引入 `redis` 模块，使用 `RedisLockProviderImpl` 与 `redis-cache`。

准入判定、管理界面、数据库表结构在四种模式下保持一致，无需分叉。

---

## 11. 实现状态

已实现的部分：

1. jaravel-vendor 应用骨架与依赖装配（`starter`、`migration`、`auth`、`jwt`、`jblade`、`cache`、`queue-database`）。
2. 三张业务表的迁移与模型（`admin_users`、`api_keys`、`backend_pools`）。
3. 统一身份认证登录、账号开通与同步、Cookie 与 Bearer 双通道的认证与授权中间件。
4. 三个管理页面（概览、API Key、GPU 后端）与管理员账号管理页面。
5. 准入判定层：会话串行、后端槽位、等级门槛、按权重分配份额、忙时 `503 + Retry-After`。
6. OpenAI 兼容转发端点 `/v1/**`，支持 SSE 逐块透传与上游鉴权替换。
7. 启动时确保配置的管理员工号存在（`app.admin.bootstrap-admin-staff-no`）。

后续工作：

1. 将准入状态（会话占用与后端槽位）迁移到共享存储，支持多实例部署。
2. 权重与后端配置的跨实例主动失效机制（当前依赖短 TTL 回源）。
3. 压测与参数调优：`Retry-After` 估算区间与权重比例。