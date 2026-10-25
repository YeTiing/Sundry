# Sundry 开发日志

记录每个开发切片：做了什么、遇到什么问题、怎么解决、怎么验证。
新条目追加在下方；顶部「当前状态」随最新进展更新。

## 当前状态（2026-10-24）

| 项 | 状态 |
|---|---|
| 后端 | Spring Boot 4.1.1 / JDK 17 / Maven 单模块 / MyBatis / Flyway |
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Vue Router；商城、购物车、地址簿、结算、订单、退款申请、后台商品与订单 |
| 数据库 | MySQL 8.0.31（本机 3306），业务库 `sundry`，测试库 `sundry_test` |
| Redis | Docker 容器 `sundry-redis`（6379，AOF + 命名卷 `sundry-redis-data`） |
| 认证 | 手机号验证码注册、验证码/密码登录、JWT + refresh 轮换、退出黑名单 |
| 权限 | SUPER_ADMIN / ADMIN / USER；超级管理员由 `app.security.super-admin-phone` 指定 |
| 存储 | MinIO `sundry-minio`（9000 API / 9001 控制台），公开桶 `sundry-public` |
| 商品图 | `POST /api/admin/files/images`，≤5MB，JPEG/PNG/WebP，随机 key |
| 商品 | 分类后台增删改、排序、停用；商品 CRUD、上下架、商城列表/详情 |
| 删除 | 商品软删除（`deleted_at`），与下架分离；后台回收站可恢复；图片和数字文件都可软删除 |
| 回收站 | 已删除商品后台可见、可恢复；恢复后保持 `OFF_SHELF`，需要重新上架 |
| 图片复用 | 新建/编辑商品可直接选择之前上传的封面图，不用重新上传 |
| 数字商品 | 私有桶、2GB 分片直传 MinIO、商品绑定 DIGITAL 文件；支付后签发 10 分钟预签名下载地址 |
| 文件清理 | 手动删除为软删除；每天 3 点清理，数字 7 天 / 图片 1 天宽限；ETag 去重 |
| 幂等 | 商品创建支持 `Idempotency-Key`，数据库唯一键兜底并发重试 |
| 名片 | 用户昵称/头像/简介；单商户店铺名片（店名/Logo/简介/联系方式/发货地址） |
| 库存/购物车 | `product.stock`；购物车同商品累加、库存校验；不锁库存 |
| 地址簿 | 收货地址 CRUD、默认地址、最多 20 条 |
| 订单 | 购物车下单、条件扣库存、模拟支付、打包/发货/确认收货、30 分钟超时取消、数字下载；后台订单带买家手机号/昵称 |
| 退款 | 用户申请（已支付/打包中且含实物）→ 商家通过/拒绝；审核前可撤销；通过后回补库存 |
| 退货 | 已发货或确认收货 7 天内申请 → 商家同意 → 用户寄回 → 商家确认收货后退款并回补库存 |
| 秒杀 | 活动管理、Redis Lua 抢购、一人一单、Kafka 异步建单、结果轮询、失败回补、ZSET 超时关单、限流、对账 |
| 缓存 | 分类/商品详情三级缓存；商品列表只缓存 ID，卡片按商品独立缓存；互斥重建、空值缓存、随机 TTL |
| 推荐 | 首页 Feed：多路召回（分类/协同/热销/新品/语义）、规则打分（分类/热度/新鲜度/标签）、多样性重排、Redis 快照、无限滑动；登录用户 AI 异步重排；曝光/点击 Kafka 异步回流；后台离线评估 |
| AI | `ai/` FastAPI 服务 + DeepSeek 重排 + 百炼 embedding；Milvus 语义召回；变体池（PREF/EXPLORE/PRICE 预生成，换一批 0 等待）；异步队列、失败降级、审计 |
| 指标 | Micrometer + Prometheus，抢购结果、建单、补偿、提交耗时 |
| 压测 | k6 脚本：商品浏览与秒杀抢购，附结果校验步骤 |
| 测试 | 211 个 Java 集成测试 + 18 个 Python 用例 |
| 分支 | `develop`、`main` 与远端保持同步（以提交记录为准） |
| 未完成 | 真实支付、退款真实资金流、通知公告、AI 助手、NL2SQL |

### 本地启动

1. MySQL 3306、Redis 6379、MinIO 9000 在跑（容器没起时：`docker start sundry-redis sundry-minio`）
   - 秒杀功能需要 Kafka：`docker compose -f docker-compose.kafka.yml up -d`（KRaft 单节点，9092）
2. IDEA：JDK 17，Active profiles 填 `local`，或 VM options 加 `-Dspring.profiles.active=local`
3. 运行 `SundryApplication`，健康检查 `http://localhost:8080/actuator/health`
4. 前端：`cd web && npm install && npm run dev`，访问 `http://localhost:5173`
5. AI 服务（可选，没启动时自动降级为规则排序）：
   - `cd ai && uv sync`
   - 复制 `ai/.env.example` 为 `ai/.env`，填 `AI_INTERNAL_TOKEN`、`DEEPSEEK_API_KEY`、
     `EMBED_API_KEY`、`EMBED_MODEL`、`EMBED_DIM`
   - `uv run --env-file .env uvicorn app.main:app --host 127.0.0.1 --port 8000`
   - 后端启动时设置同样的 `AI_INTERNAL_TOKEN`；`AI_BASE_URL` 默认 `http://127.0.0.1:8000`
6. Milvus（可选，语义召回；没起时自动跳过这一路）：
   - `docker compose -f docker-compose.milvus.yml up -d`
   - gRPC 19530、健康检查 9091；Milvus 内部 MinIO 映射到 9100/9101
   - 重建向量：`POST /api/admin/feed/embeddings/rebuild`（管理员，约 15 秒）
   - 不需要时后端设 `MILVUS_ENABLED=false`
7. 测试：`TEST_DB_PASSWORD=123456 mvn test`；Python：`cd ai && uv run pytest -q`

超级管理员手机号在 `application-local.yml` 的 `app.security.super-admin-phone`。改成自己的号并重启，用该号注册即成为超级管理员。MinIO 控制台：`http://localhost:9001`（账号 `sundry`）。

开发环境短信不发真短信，验证码看后端日志：`[dev-sms] phone=... code=xxxxxx`。

---

## 2026-10-22 · 项目初始化与远端

**做了什么**

- 建立仓库骨架：`CLAUDE.md`、`README.md`、`.gitignore`、`.editorconfig`
- `git init`（main），关联 `https://github.com/YeTiing/Sundry.git`，首次推送

**遇到问题**

- `.editorconfig` 要求 LF，但全局 `core.autocrlf` 会在检出时转 CRLF，git 给出换行符警告

**解决**

- 新增 `.gitattributes`：`* text=auto eol=lf`
- 执行 `git add --renormalize .`，用 `git ls-files --eol` 验证全部为 `i/lf w/lf`

**提交**

- `cc8bee3` chore: 初始化项目骨架
- `ead9c62` chore: 统一换行符为 LF

---

## 2026-10-22 · 技术底座与环境

**做了什么**

- 确认本机：JDK 17.0.16（`JAVA_HOME=D:\JAVA17`）、Maven 3.9.11、MySQL 8.0.31、Docker
- 选型：Spring Boot 4.1.1、MyBatis Spring Boot Starter 4.1.0、Flyway、MySQL、Redis
- 建立 `pom.xml`、启动类、`application.yml` / `application-local.yml` / `application-test.yml`
- Flyway `V1__create_app_user.sql` 建立 `app_user` 表，`phone` 唯一索引
- actuator 只暴露 `/actuator/health`

**遇到问题**

- Redis 未安装，6379 无监听；Docker Desktop 装了但守护进程没启动
- PATH 里默认的 `java` 是 11，和项目要求的 17 不一致

**解决**

- 启动 Docker 后拉取 `redis:7-alpine`，运行 `sundry-redis`，开启 AOF 并挂命名卷
- 统一使用 JDK 17；IDEA 的 Project SDK 和 Run 配置都指向 `D:\JAVA17`

**提交**

- 骨架与认证一起提交在 `313dc9c`

---

## 2026-10-22 · 注册登录切片（后端）

**做了什么**

- 接口：
  - `POST /api/auth/sms-code`
  - `POST /api/auth/register`
  - `POST /api/auth/login/code`
  - `POST /api/auth/login/password`
  - `POST /api/auth/refresh`
  - `POST /api/auth/logout`
  - `GET /api/auth/me`
- Redis 键与限制：
  - `auth:sms:code:{scene}:{phone}`，TTL 5 分钟
  - `auth:sms:attempt:{scene}:{phone}`，最多输错 5 次
  - `auth:sms:cooldown:{phone}`，60 秒冷却
  - `auth:sms:daily:{phone}:{date}`，每手机号每日 10 条
- 验证码用 Lua 在 Redis 内原子校验并消费，避免并发重放
- 密码 BCrypt 哈希入库；手机号唯一索引兜底并发注册
- JWT HS256：access 30 分钟；refresh 7 天、一次性使用、每次刷新轮换
- refresh token 存 HttpOnly Cookie（`Path=/api/auth`，`SameSite=Lax`）
- 退出：删除 refresh；access 的 `jti` 加入 Redis 黑名单，TTL 为剩余有效期
- 统一错误码 `ErrorCode` + 全局异常处理 `GlobalExceptionHandler`

**遇到问题**

- 没有真实短信通道
- 集成测试需要真实 MySQL 和 Redis
- 密码不能用可逆加密存储

**解决**

- `SmsSender` 接口 + `LoggingSmsSender`（`app.sms.provider=log`），生产再替换真实实现
- 新建 `sundry_test` 库，测试连真实 MySQL + Redis 15 号库，`@ActiveProfiles("test")`
- 使用 `BCryptPasswordEncoder`，只存哈希

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 10 个用例全部通过
- 覆盖：注册、密码登录、验证码登录、refresh 轮换、旧 refresh 失效、退出后 access 失效、重复手机号、错误验证码、错误次数上限、发送限流、非法手机号

**提交**

- `313dc9c` feat: 实现手机号验证码注册登录与 JWT 鉴权

---

## 2026-10-22 · 登录注册页面（前端）

**做了什么**

- `web/`：Vue 3 + Vite + TypeScript + Pinia + Vue Router，不引 UI 框架和 axios
- 页面：登录（验证码 / 密码两个标签）、注册、登录后首页
- access token 只放内存；刷新页面时用 refresh Cookie 恢复登录态
- 请求遇 401 自动刷新一次并重试；刷新失败则回登录页
- 验证码按钮 60 秒倒计时；前后端都校验手机号和密码格式
- Vite 将 `/api` 代理到 `127.0.0.1:8080`，开发时同源，Cookie 正常携带

**遇到问题**

- `npm install` 从默认源拉包时出现 `ECONNRESET`

**解决**

- 改用国内镜像 `npmmirror` 重试，90 个包安装成功；`npm run build` 通过

**提交**

- `75cf905` feat: 新增 Vue 登录注册页面

---

## 2026-10-22 · 后端注释

**做了什么**

- 33 个文件补充注释：Spring / Web / 参数校验 / MyBatis / Spring Security / JUnit 注解说明
- 关键逻辑注释：Lua 原子消费、SETNX 冷却、BCrypt、`jti` 黑名单、refresh 轮换、Cookie 属性

**验证**

- `mvn test` 仍为 10/10

**提交**

- `7b1a0b1` docs: 补充后端注解与关键逻辑注释

---

## 2026-10-22 · 启动排错（IDEA）

**现象 1**

- 启动报 `java.lang.IllegalStateException: app.jwt.secret 未配置`
- 日志提示 `No active profile set, falling back to "default"`

**原因 1**

- 密钥在 `application-local.yml`，只有激活 `local` 才加载；`JwtService` 启动时故意检查，空密钥直接失败

**解决 1**

- IDEA Run Configuration：Active profiles 填 `local`，或 VM options 加 `-Dspring.profiles.active=local`

**现象 2**

- 改后报 `Profile 'SPRING_PROFILES_ACTIVE=local' must contain a letter, digit or allowed char`

**原因 2**

- 把环境变量赋值写进了「Active profiles」栏，整串被当成一个 profile 名

**解决 2**

- Active profiles 只填 `local`
- 若用环境变量方式，应写在 Environment variables 输入框（`KEY=VALUE` 格式）

---

## 2026-10-22 · 角色权限与商品图上传

**做了什么**

- Flyway V2：`app_user` 增加 `role`，新增 `file_object` 表
- 角色三层：`SUPER_ADMIN` / `ADMIN` / `USER`
  - 超级管理员由 `app.security.super-admin-phone` 指定
  - 该手机号注册时直接赋 `SUPER_ADMIN`；启动时若账号已存在则提升
- JWT 过滤器每次按用户 ID 读库，把 `ROLE_*` 写入权限列表
  - 提升/降级立即生效，不用等令牌过期
  - 被禁用用户直接不放行
- 开启 `@EnableMethodSecurity`，管理接口用 `@PreAuthorize`
- 用户管理（仅超级管理员）：
  - `GET /api/admin/users?page=1&size=20`，size 上限 100
  - `PATCH /api/admin/users/{id}/role`，只能设为 ADMIN 或 USER
  - SQL 带 `role <> 'SUPER_ADMIN'` 前置条件，超级管理员不可被接口改掉
- MinIO：
  - 容器 `sundry-minio`，9000 API / 9001 控制台，命名卷 `sundry-minio-data`
  - 启动时自动建公开桶 `sundry-public` 并设置只读策略
- 图片上传：
  - `POST /api/admin/files/images`，multipart
  - 校验大小、魔数（JPEG/PNG/WebP），不信任客户端 Content-Type
  - key 为 `products/yyyy/MM/{uuid}.{ext}`，原始文件名只入库展示
  - 写 MinIO 后记录 `file_object`；数据库失败时删除对象
- 前端 `/admin`：
  - 图片选择/拖拽、预览、上传、复制链接
  - 超级管理员可见用户列表、角色调整、分页
  - 路由守卫按角色限制

**遇到问题**

- MinIO Java SDK 9.0.3 依赖 OkHttp 5 的 KMP 包，Maven 下载到的 `okhttp` jar 只有元数据，编译报 `okhttp3.HttpUrl` 不存在
- 解决：降到 8.5.17（依赖 OkHttp 4.12 普通 JVM jar）
- `MissingServletRequestPartException` 包路径写错，应为 `org.springframework.web.multipart.support`
- Windows 下 Git Bash 的 `/tmp` 路径传给 Windows curl 会报 26（无法读取文件），改用 `D:/...` 路径
- Spring multipart 超限时还没进业务层，补 `MaxUploadSizeExceededException` 处理
- `@PreAuthorize` 拒绝默认会被兜底成 500，补 `AccessDeniedException` → 403
- 前端 `fetch` 上传 FormData 时不能手动设 `Content-Type`，否则丢 multipart 边界

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 22 个用例全部通过（原 10 + 新增 12）
- 前端 `npm run build` 通过
- 8081 临时实例跑真实链路：
  - 注册配置手机号 → 返回 `role: SUPER_ADMIN`
  - 上传 70B PNG → 返回 MinIO URL
  - 直接访问该 URL → 200，`image/png`
  - `file_object` 写入记录
- 8081 实例已停止，未影响 8080

**提交**

- `c3c2ef4` feat: 新增角色权限与商品图上传

---

## 2026-10-22 · 商品与分类展示

**做了什么**

- Flyway V3：
  - `category` 表，种子分类：秒杀券、软件、实物
  - `product` 表：名称、描述、价格 `DECIMAL(10,2)`、封面 `file_object.id`、分类、状态
  - 状态：`DRAFT` / `ON_SALE` / `OFF_SHELF`，暂无 SKU 和库存
- 公开接口：
  - `GET /api/categories`
  - `GET /api/products?categoryId=&page=&size=`，只返回 `ON_SALE`，支持分类筛选和分页
  - `GET /api/products/{id}`，非上架返回 404
- 管理接口（ADMIN / SUPER_ADMIN）：
  - `GET /api/admin/products`，可看全部状态、分页
  - `POST /api/admin/products`、`PUT /api/admin/products/{id}`
  - `PATCH /api/admin/products/{id}/status` 上下架
- 封面复用图片上传：存 `file_object.id`，读取时按存储配置拼 URL
- 前端：
  - `/` 改为商城列表：分类筛选、分页、商品卡片
  - `/products/:id` 商品详情
  - `/account` 账户页
  - `/admin/products` 商品管理：新建/编辑、封面上传、上下架、分页
  - 顶部导航随登录状态和角色变化

**遇到问题**

- 编辑商品时要保留原封面，`ProductView` 原先只返回 `coverUrl`，补了 `coverFileId`
- Windows Git Bash 直接传中文 JSON 会因终端编码导致 Jackson 解析失败；改用 UTF-8 文件 `--data-binary` 验证通过。浏览器 `fetch` 本身按 UTF-8 发送，不受影响

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 28 个用例全部通过（新增商品 6 个）
- 前端 `npm run build` 通过
- 8081 真实链路：
  - 超级管理员创建 `ON_SALE` 商品
  - 公开列表和详情都返回分类名、价格、封面 URL
  - 中文名称和多行描述写入、读取正常
  - 封面对应的 MinIO URL 返回 200 `image/png`
- 8081 实例已停止

**提交**

- `fd2e606` feat: 新增商品分类与商城展示

---

## 2026-10-22 · 数字文件分片直传

**做了什么**

- 引入 AWS SDK v2 S3（MinIO Java SDK 8.5 不提供显式 create/complete multipart）
- 私有桶 `sundry-private`，启动时自动创建；不设置公开策略
- 上传接口（ADMIN / SUPER_ADMIN）：
  - `POST /api/admin/uploads/digital/init`：校验 2GB 上限，创建 multipart，返回每个分片的预签名 PUT URL
  - `POST /api/admin/uploads/digital/complete`：后端 ListParts 获取 ETag 完成上传，HeadObject 校验大小，写 `file_object(kind=DIGITAL)`
  - `POST /api/admin/uploads/digital/abort`：取消并清理
- Redis 保存上传会话（uploaderId / key / size / partCount），防止伪造完成
- 失败补偿：完成失败、大小不符、数据库写入失败都会删除对象或取消 multipart
- 商品 V4：
  - `product.type`：`PHYSICAL` / `DIGITAL`
  - `product.digital_file_id` → `file_object`
  - 数字商品必须绑定 DIGITAL 文件；实物商品不能绑定
- 前端：
  - 商品表单增加商品类型
  - `DigitalFileUploader`：选文件、3 并发分片直传、进度、取消、移除
  - 商品列表显示类型
  - 私有文件不在公开商品接口里暴露地址

**遇到问题**

- MinIO 不支持 `PutBucketCors`，AWS SDK 调用返回 501，应用启动失败
- 解决：不再依赖 CORS 暴露 ETag；浏览器只负责 PUT 分片，完成时后端 `ListParts` 拿 ETag 再完成 multipart
- MinIO Java SDK 没有显式 multipart API
- 解决：引入 AWS SDK v2 S3，只用于分片创建/签名/完成
- `partNumberMarker` 参数要求 Integer，不是 String
- AWS SDK 默认校验和可能影响预签名，客户端设置 `RequestChecksumCalculation.WHEN_REQUIRED`

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 35 个用例全部通过（新增数字上传 7 个）
- 前端 `npm run build` 通过
- 8081 真实链路：
  - 20MB 文件切成 2 片，两片 PUT 均返回 200
  - complete 返回 `sizeBytes=20971520`
  - 创建 DIGITAL 商品并绑定 file_object
  - 私有桶对象匿名 GET 返回 403
- 8081 实例已停止

**补充：取消与清理（同日）**

- 前端取消上传：abort 所有 XHR 并调用 abort 接口，取消 multipart、删除 Redis 会话
- 移除已上传文件：新增 `DELETE /api/admin/uploads/digital/{id}`，确认未被商品引用后删除 MinIO 对象和 `file_object`
- 文件仍被商品引用时返回 409 `FILE_IN_USE`，避免误删在售商品的文件
- 关闭页面：`pagehide` 时用 `fetch keepalive` 自动取消未完成上传
- 编辑商品更换或移除数字文件：商品保存成功后清理旧文件
- 验证：38 个测试通过；真实链路删除后数据库记录为 0，被引用文件返回 409

**提交**

- `a13afe8` feat: 新增数字文件分片直传与商品绑定
- `57eaa75` feat: 支持取消上传与删除数字文件

---

## 2026-10-23 · 文件软删除、ETag 去重与定时清理

**做了什么**

- V5：`file_object` 增加 `etag`、`deleted_at`，补去重索引和清理索引
- 手动删除改为**软删除**：
  - 只允许删除没有被任何商品引用的文件
  - `status = 0`、记录 `deleted_at`，MinIO 对象保留
  - 数字文件保留 7 天，期间重复上传可复用
- 定时清理 `FileCleanupJob`：
  - 每天 3 点执行，cron 可配置
  - Redis 锁防止多实例/重叠执行
  - 数字文件宽限 7 天，封面图 1 天
  - 清理两类：软删除过期文件；无商品引用且超过宽限期的孤儿文件
  - 批量 100，单次最多 10 批
- ETag 去重：
  - 上传完成时用 `HeadObject` 的 ETag + 文件大小 + 上传人判断
  - 命中则删掉新对象，复用旧 `file_object`
  - 命中软删除记录时恢复 `status = 1`
  - 注意：只省存储，不省上传流量（ETag 要传完才知道）
- complete 幂等：
  - 成功后 Redis 缓存结果 1 小时
  - 网络超时重试会返回同一个 fileId，不会报「会话不存在」
- 订单上线后，清理条件还要增加「没有订单引用」

**遇到问题**

- 会话恢复后出现两个 V5 迁移文件，Flyway 报「more than one migration with version 5」
- 解决：删掉重复文件，并清理 `target/classes` 里的旧副本
- Docker Desktop 未启动，Redis / MinIO 连不上
- 解决：拉起 Docker Desktop，启动 `sundry-redis` 和 `sundry-minio`

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 48 个用例全部通过（含复用文件 3 个）
- 真实 MinIO：
  - 同一个 1MB 文件传两次 → 返回同一个 fileId（ETag 去重）
  - 软删除后 `status = 0`
  - 再传同一个文件 → 恢复同一个 fileId，`status = 1`，数据库只剩一行
- 8081 实例已停止

**补充：直接复用已上传文件**

- 后台商品表单增加「选择已上传」
- `GET /api/admin/uploads/digital/reusable`：列出有效文件和 7 天内软删除的文件
- `POST /api/admin/uploads/digital/{id}/reuse`：恢复软删除记录或刷新有效文件时间，**不重新上传**
- 复用后重置 `created_at`，避免刚恢复就被清理任务当成长期孤儿
- 真实 MinIO 验证：软删除文件出现在列表，复用后 `status = 1`

**提交**

- `ba803d2` feat: 文件软删除、ETag 去重与定时清理
- `74d1bf5` feat: 支持直接复用已上传文件

---

## 2026-10-23 · 商品软删除与图片文件删除

**做了什么**

- V6：`product` 增加 `deleted_at`，商品支持软删除
- 明确区分两个动作：
  - **下架**：`status = OFF_SHELF`，`deleted_at = null`；后台仍可见，可以重新上架
  - **删除**：`deleted_at` 有值，同时 `status` 置为 `OFF_SHELF`；前后台都不再展示，不能重新上架
- 删除商品**不删除文件**：
  - `cover_file_id` / `digital_file_id` 保留
  - 已售数字商品的下载权益不会被破坏
  - 文件删除接口会返回 409 `FILE_IN_USE`
- 文件通用软删除：`DELETE /api/admin/files/{id}`
  - 图片和数字文件都支持
  - 同时检查 `product.cover_file_id` 和 `product.digital_file_id`
  - 软删除后按宽限期清理：数字 7 天 / 图片 1 天
- 前端：
  - 商品列表增加「删除」按钮 + 二次确认
  - 图片上传结果增加「删除图片」按钮
- 商品列表查询统一加 `deleted_at IS NULL`；已删除商品不在前台和后台列表展示

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 54 个用例全部通过
  - 下架不等于删除，下架后可重新上架
  - 删除后前后台都不可见，详情 404，不能再上架
  - 文件仍被软删除商品引用，删除文件返回 409
  - 图片软删除后 `status=0`，对象保留
- 前端 `npm run build` 通过

**提交**

- `c279bcc` feat: 商品软删除与图片文件删除

---

## 2026-10-23 · 商品回收站与图片复用

**做了什么**

- 商品回收站：
  - `GET /api/admin/products/deleted` 分页列出软删除商品
  - `POST /api/admin/products/{id}/restore` 恢复商品
  - 恢复后保持 `OFF_SHELF`，需要商家重新上架才会出现在商城
  - 后台商品页增加「回收站」切换和「恢复」按钮
- 封面图复用：
  - `GET /api/admin/files/images/reusable` 列出当前上传人的有效图片 + 1 天内软删除图片
  - `POST /api/admin/files/images/{id}/reuse` 恢复/刷新记录并返回图片 URL
  - 新建或编辑商品时可以直接选历史封面，不用重新上传
- 商品和文件复用都按上传人隔离；跨管理员共享属于权限变更，暂未开放

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 56 个用例全部通过
- 前端 `npm run build` 通过
- 回收站：删除后出现在回收站，恢复后回到后台列表（`OFF_SHELF`），可重新上架
- 图片复用：软删除图片出现在复用列表，复用后 `status = 1`

**提交**

- `1d49586` feat: 商品回收站与图片复用

---

## 2026-10-23 · 商品创建幂等

**做了什么**

- 前端每次「创建商品」生成一个 `Idempotency-Key`：
  - 请求失败/网络超时后重试，复用同一个 key
  - 创建成功后重置；下一次创建是新 key
- 后端 `POST /api/admin/products` 接收 `Idempotency-Key`，长度限制 64
- V7：`product.request_id` + 唯一索引
- 创建逻辑：
  - 先按 `request_id` 查，命中直接返回首次创建的商品
  - 插入时并发撞唯一索引，捕获 `DuplicateKeyException` 后再查一次并返回
  - 同一 key 不会插入第二行
- 前端按钮 `saving` 禁用 + `save()` 入口并发保护，作为第一层防抖

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 58 个用例全部通过
  - 同一 key 连续创建两次 → 同一个商品 id，数据库只有 1 行
  - 不同 key → 两个商品
  - key 超过 64 字符 → 400 `VALIDATION_FAILED`
- 前端 `npm run build` 通过

**方案取舍：为什么不用 `INSERT ... ON DUPLICATE KEY UPDATE`**

曾考虑用一条 SQL 同时完成「插入或取回已有 id」：

```sql
INSERT INTO product (...)
VALUES (...)
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
```

最终没有采用，保持「预查 + 插入 + 撞唯一键再查」。

| 维度 | 当前方案（预查 + 唯一索引兜底） | ON DUPLICATE KEY UPDATE |
|---|---|---|
| 首次创建 | 1 次索引预查 + INSERT + 回查 | INSERT + 回查，少 1 次读 |
| 重复重试 | 1 次索引预查直接返回，不校验、不写 | 先跑分类/封面/文件校验，再 upsert，再回查 |
| 多唯一键 | 可按 request_id 精确判断 | 对所有唯一键生效；将来 product_code 冲突会被误当成幂等命中 |
| 主键回填 | `useGeneratedKeys` 标准路径，稳定 | 依赖 `LAST_INSERT_ID(id)` 和驱动行为，需要专门验证 |
| 返回完整商品 | 已有 row，直接映射 | 仍需按 id 回查一次 |
| 写语义 | 重试只读 | 重试也执行一次「空更新」，走 UPDATE 路径 |
| 锁 / 触发器 / binlog | 重试不写，干净 | 可能多持锁、触发 UPDATE 触发器、binlog 记 UPDATE |
| 可移植性 | 标准 SQL + 唯一索引 | MySQL 方言 |
| 代码复杂度 | 低，路径清晰 | 表面简单，隐式语义多 |

结论：

- 商品创建是管理员低频操作，少一次索引读的收益可以忽略
- 幂等重试应该是只读的；`ON DUPLICATE` 会把重试变成写操作
- 现在只有一个唯一键，但后续可能加 `product_code`；`ON DUPLICATE` 无法区分是哪个键冲突，风险大于收益
- 保持当前方案：预查走唯一索引，并发由数据库唯一键兜底

**提交**

- `db32898` feat: 商品创建幂等
- `839f765` fix: 创建商品增加并发进入保护

---

## 2026-10-23 · 用户名片与店铺名片

**做了什么**

- V8：
  - `app_user` 增加 `nickname`、`avatar_file_id`、`bio`
  - 新增 `shop_profile` 单行表（店名、Logo、简介、联系方式）
- 用户名片：
  - `GET /api/profile`、`PUT /api/profile`
  - `POST /api/profile/avatar`，2MB 以内，JPEG/PNG/WebP
  - 登录、刷新、`/api/auth/me` 返回昵称、头像 URL、简介
- 店铺名片：
  - `GET /api/shop` 公开
  - `GET/PUT /api/admin/shop` 仅超级管理员
  - 商品详情页展示店铺卡：Logo、店名、简介、联系方式
- 前端：
  - 账户页编辑昵称/简介、上传头像
  - 顶部导航显示头像 + 昵称
  - 后台新增「店铺资料」编辑
- 单商户模型：
  - 所有商品同一卖家，**不给 product 加 seller_id**
  - 卖家信息用店铺名片统一展示

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 63 个用例全部通过
  - 昵称/简介更新并回显
  - 头像上传、2MB 限制
  - 店铺公开读取、超级管理员编辑、普通用户 403
  - 店铺 Logo 上传并回显
- 前端 `npm run build` 通过
- 8081 + 真实 MinIO：中文昵称/简介、头像、店铺 Logo、公开店铺接口均验证通过；测试数据已恢复

**提交**

- `e5403c6` feat: 用户名片与店铺名片

---

## 2026-10-23 · 库存 + 购物车 + 地址簿（后端）

**做了什么**

- V9：
  - `product.stock`，实物商品参与库存，数字商品忽略
  - `cart_item`：`(user_id, product_id)` 唯一，重复加入累加数量
  - `user_address`：收货人、电话、省市区、详细地址、默认标记
- 购物车接口：`GET/POST/PUT/DELETE /api/cart`
  - 加入时校验商品已上架、实物库存足够
  - 购物车不锁库存，真正扣减在下单
- 地址簿接口：`GET/POST/PUT/DELETE /api/addresses` + `POST /{id}/default`
  - 最多 20 条
  - 第一条自动默认；删除默认地址后自动把剩下第一条设为默认
- 下单策略确定：
  - 不上 Redis、不做秒杀
  - 数据库事务 + `(user_id, request_id)` 唯一索引 + 条件扣库存

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 66 个用例全部通过
- 覆盖：购物车累加、库存不足、修改/删除、地址 CRUD、默认地址切换、库存回显

**待做**

- 前端购物车页、地址页、商品库存字段
- 下单 + 模拟支付 + 订单状态机

**提交**

- `54b2a34` feat: 库存字段与购物车地址簿

---

## 2026-10-23 · 订单、模拟支付与履约（后端）

**做了什么**

- V10：`orders` + `order_item`
  - 订单号唯一、`(user_id, request_id)` 唯一（下单幂等）
  - 订单项快照：商品名、类型、价格、数量、封面 URL、数字文件 id
- 下单（购物车选中项）：
  - 事务内条件扣库存，任何一件不足整体回滚
  - 实物商品必须选收货地址，地址快照进订单
  - 下单成功后清空对应购物车条目
- 模拟支付：
  - `POST /api/orders/{orderNo}/pay`，条件更新 `PENDING_PAYMENT → PAID`
  - 重复支付幂等
- 状态机：
  - 待支付 → 已支付 → 打包中 → 已发货 → 已完成
  - 并发全部用带前置状态的 UPDATE
- 取消与超时：
  - 用户取消未支付订单，回补库存
  - 每分钟扫描超过 30 分钟未支付订单，自动取消并回补库存
- 商家：
  - 订单列表/详情
  - 打包、发货（快递单号）
- 用户：
  - 订单列表/详情、支付、取消、确认收货
- 数字商品：
  - 支付后可下载，签发 10 分钟预签名 GET 地址
  - 允许重复下载
- 文件清理加了订单引用保护：被订单引用的数字文件不会被清理任务删除，也不能通过删除接口删掉

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 72 个用例全部通过
  - 下单扣库存、幂等下单、重复支付
  - 打包 → 发货 → 确认收货
  - 取消回补库存、超时取消
  - 库存不足回滚事务
  - 数字商品支付前 403、支付后返回预签名下载地址
  - 订单引用的文件不会被清理
- 不依赖 Redis、不做秒杀

**待做**

- 前端：商品库存字段、购物车页、地址页、结算/订单页、后台订单页
- 真实支付渠道接入
- 退款售后

**提交**

- `c4d4892` feat: 订单、模拟支付与履约流程

---

## 2026-10-24 · 退款申请与商家审核（后端）

**做了什么**

- V11：`orders` 增加 `refunded_at`、`refund_amount`；新增 `order_refund` 申请流水表
- 订单状态新增 `REFUND_REQUESTED`、`REFUNDED`
- 规则：
  - 只有 `PAID` / `PACKING` 且含实物的订单能申请退款
  - 待支付走取消；已发货、已完成、纯数字订单不支持退款
  - 整单全额退款，不做部分退款
- 用户接口：
  - `POST /api/orders/{orderNo}/refund`，原因必填、≤200 字
  - `POST /api/orders/{orderNo}/refund/withdraw`，审核前可撤销
- 商家接口（ADMIN / SUPER_ADMIN）：
  - `POST /api/admin/orders/{orderNo}/refund/approve`，备注选填
  - `POST /api/admin/orders/{orderNo}/refund/reject`，拒绝理由必填
  - 待审核列表复用 `GET /api/admin/orders?status=REFUND_REQUESTED`
- 审核期间订单卡在 `REFUND_REQUESTED`，打包/发货/确认收货的条件更新都不成立
- 通过后订单 `REFUNDED`、`refund_amount = total_amount`，同一事务回补实物库存
- 拒绝或撤销回到申请前状态（`prev_status`），库存不动；被拒后可重新申请，流水保留历史
- 退款后数字商品下载返回 403 `ORDER_REFUNDED`
- 幂等与并发：
  - 重复申请返回当前订单，只产生一条 PENDING 流水
  - 重复通过返回已退款订单，库存只回补一次
  - 申请/通过/拒绝/撤销全部用带前置状态的条件更新

**遇到问题**

- 退款需要保留申请与审核痕迹，单靠订单字段无法支撑“被拒后重新申请”的历史

**解决**

- 订单主状态仍放 `orders.status`，申请明细单独放 `order_refund`，两者在同一事务更新

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 82 个用例全部通过（新增 10 个）
  - 申请 → 审核通过 → 库存回补；重复通过幂等
  - 打包中申请、拒绝后回到 `PACKING` 并可继续发货
  - 被拒后重新申请，历史流水保留
  - 审核前撤销；重复撤销幂等
  - 待支付、已发货、已完成、纯数字订单拒绝退款
  - 他人订单 404、普通用户审核 403、空原因 400
  - 混合订单退款后数字下载 403
  - 并发申请只产生一条流水；并发通过库存只回补一次

**提交**

- `f478b5b` feat: 退款申请与商家审核

## 2026-10-24 · 前端：库存字段、购物车与地址簿

**做了什么**

- 商品表单增加库存字段（仅实物），商品列表增加库存列；库存随商品保存
- 商品详情：显示库存/数字商品提示，数量选择 + 加入购物车；未登录跳登录并回跳
- 购物车页 `/cart`：
  - 列表、数量增减与输入、移除、合计
  - 已下架商品置灰、不计入合计
- 收货地址页 `/addresses`：
  - 列表、新建、编辑、删除、设为默认
  - 第一条自动默认（后端规则），最多 20 条
- 顶部导航增加「购物车」入口；账户页增加购物车、地址簿入口
- 新增 `cartApi`、`addressApi`，类型补齐 `stock`、`CartItem`、`Address`

**验证**

- `npm run build`（vue-tsc + vite）通过
- 未改后端；接口沿用现有 `/api/cart`、`/api/addresses`

**提交**

- `3f0423d` feat: 前端库存字段、购物车与地址簿

## 2026-10-24 · 前端：结算与订单

**做了什么**

- 结算页 `/checkout`：
  - 读取购物车，默认全选可购买商品，可逐项取消
  - 收货地址单选，默认选中默认地址；含实物时必须选地址
  - 提交订单带 `Idempotency-Key`，失败重试复用同一个 key
- 订单页 `/orders`：
  - 分页列表、状态标签、商品明细、收货信息、快递单号
  - 待支付：去支付（模拟）、取消订单
  - 已发货或纯数字已支付：确认收货
  - 已支付/打包中的实物订单：申请退款（原因必填 ≤200）、审核中可撤销
  - 退款状态、商家回复、退款金额与时间展示
  - 数字商品在支付后显示下载按钮，使用后端 10 分钟预签名地址
- 购物车「去结算」接上结算页
- 顶部导航、账户页增加「我的订单」

**验证**

- `npm run build`（vue-tsc + vite）通过，67 个模块
- 后端未改

**提交**

- `5bd371e` feat: 前端结算与订单页

## 2026-10-24 · 前端：后台订单管理与退款审核

**做了什么**

- 新增 `/admin/orders`：
  - 按状态筛选、分页、刷新
  - 订单卡片展示商品、收货信息、快递单号、退款申请详情
  - 待发货：打包；打包中：填写快递单号发货
  - 退款审核中：通过（备注选填，库存回补）或拒绝（理由必填，订单回原状态）
- 新增 `adminOrdersApi`
- 商品管理、图片与用户页导航增加「订单管理」

**验证**

- `npm run build`（vue-tsc + vite）通过，70 个模块
- 后端未改，复用 `/api/admin/orders` 现有接口

**提交**

- `865cce7` feat: 后台订单管理与退款审核

## 2026-10-24 · 店铺发货地址与订单买家信息

**做了什么**

- V12：`shop_profile` 增加 `shipping_address`
- 店铺名片新增「发货地址」：超级管理员在 `/admin` 编辑，公开 `/api/shop` 也会返回
- 后台订单页：
  - 每单显示买家手机号和昵称，纯数字订单也能看到是谁买的
  - 发货表单显示店铺发货地址；未设置时给出去店铺资料的入口
- 后端 `OrderView` 增加 `buyerPhone`、`buyerNickname`，由 `app_user` 补充

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 82 个用例全部通过
  - 店铺资料更新和公开读取包含发货地址
  - 后台订单列表返回买家手机号
- `npm run build` 通过

**提交**

- `9656476` feat: 店铺发货地址与订单买家信息

## 2026-10-24 · 补充：管理员账户页隐藏买家入口

**做了什么**

- 账户页和顶部导航对 ADMIN / SUPER_ADMIN 隐藏「购物车」「我的订单」「收货地址」
- 超管账户页改为「店铺发货地址」入口，跳到管理后台的店铺资料
- 普通用户菜单不变

**验证**

- `npm run build` 通过

**提交**

- `5ec1ace` fix: 管理员账户页隐藏买家入口

## 2026-10-24 · 退货退款

**做了什么**

- V13：`order_refund` 增加 `kind`、`return_tracking_no`、`return_shipped_at`、`received_at`
- 订单状态新增 `RETURN_REQUESTED`、`RETURNING`、`RETURN_SHIPPED`
- 流程：
  - 已发货，或确认收货后 7 天内（按 `completed_at`）且含实物，可以申请退货
  - 商家同意 → 等待用户寄回；拒绝或用户撤销 → 回到原状态
  - 用户填写退货快递单号 → 等待商家收货
  - 商家确认收到 → 订单 `REFUNDED`，同一事务回补库存
- 退货不建模退货运费，整单全额退款
- 接口：
  - 用户：`POST /api/orders/{orderNo}/return`、`/return/withdraw`、`/return/ship`
  - 商家：`POST /api/admin/orders/{orderNo}/return/approve`、`/return/reject`、`/return/receive`
- 前端：
  - 用户订单页：申请退货、撤销、填写退货单号、退货进度展示
  - 后台订单页：同意/拒绝退货、确认收到退货
- 所有状态变更仍是条件更新；确认收货的回补只在状态成功流转时执行

**遇到问题**

- 退款和退货共用 `revertRefund` 回退状态，原 SQL 只匹配 `REFUND_REQUESTED`，退货拒绝/撤销返回 409

**解决**

- `revertRefund` 同时匹配 `REFUND_REQUESTED` 和 `RETURN_REQUESTED`

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 92 个用例全部通过（新增 10 个退货用例）
  - 完整退货链路、确认收货才回补库存、重复确认幂等
  - 拒绝和撤销回到 `SHIPPED`
  - 确认收货 7 天内可退、超过 7 天 `RETURN_WINDOW_EXPIRED`
  - 未发货、纯数字、未同意先寄回均被拒
  - 越权、角色、空原因
  - 并发申请只产生一条流水；并发确认收货只回补一次
- `npm run build` 通过

**提交**

- `5456981` feat: 退货退款流程

## 2026-10-24 · 秒杀 S1：活动模型与后台管理

**做了什么**

- V14：新增 `seckill_activity`（商品、秒杀价、活动库存、已售数、每人限购、开始/结束时间、状态）
- 活动状态：`DRAFT / ACTIVE / ENDED / CANCELLED`；是否正在进行还要结合时间窗口判断
- 后台接口：
  - `GET/POST /api/admin/seckill/activities`
  - `PUT /{id}`（仅草稿）
  - `POST /{id}/activate|cancel|end`
- 公开接口：`GET /api/seckill/products/{productId}`，只返回当前进行中的活动
- 校验：
  - 商品必须存在、未删除、已上架
  - 秒杀价大于 0 且不高于原价
  - 实物活动库存不能超过商品库存；数字商品只受活动配额限制
  - 同一商品不能有重叠的进行中活动
  - 结束时间必须晚于开始时间且在将来
- 后台页面 `/admin/seckill`：创建/编辑草稿、启用、结束、取消、状态筛选、分页
- 商品详情：显示秒杀标记、秒杀价、原价划线、剩余数量与活动时间；活动期间普通加购暂时禁用
- 后台导航增加「秒杀活动」

**说明**

- 这一片不引入 Kafka；抢购、Redis 扣减、异步建单在后续切片
- 数字商品活动库存是销售配额，不动 `product.stock`

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 101 个用例全部通过（新增 9 个秒杀活动用例）
- `npm run build` 通过

**提交**

- `045588e` feat: 秒杀活动模型与后台管理

## 2026-10-24 · 秒杀 S2：Redis 抢购与同步建单

**做了什么**

- V15：`orders` 增加 `activity_id`、`order_source`；唯一索引 `(activity_id, user_id)` 防一人多单
- 订单号改成雪花算法：41 位时间 + 10 位 workerId + 12 位序列
  - `app.order.worker-id` 配置，多实例必须不同，取值 0..1023
  - 时钟回拨超过 5ms 直接拒绝生成
- Redis：
  - `seckill:stock:{activityId}`：活动剩余配额
  - `seckill:buyer:{activityId}`：用户购买标记 Hash
  - `seckill:warm:{activityId}`：预热锁，SETNX + owner token
  - Lua 原子完成「判重 + 判库存 + 扣减 + 标记用户」
- 预热：
  - 活动启用时预热
  - 应用启动时补齐所有进行中的活动
  - key 丢失时懒重建：按 DB 的 `stock - sold_count` 和订单表购买者重建
- 秒杀入口 `POST /api/seckill/orders`：
  - 独立入口，不经过购物车；每人固定 1 件
  - 实物必须传地址，数字商品不需要
  - 先 Redis 预占，再数据库事务：活动 `sold_count+1` + 实物扣商品库存 + 写订单
  - 建单失败回补 Redis 并移除购买标记；重复建单（唯一索引）返回已有订单
- 取消/超时关单：回补活动配额和 Redis 库存，但保留购买标记，同一用户不能重复抢
- 前端：
  - 新增抢购页 `/seckill/:activityId`：活动价、剩余数量、地址选择、立即抢购
  - 商品详情「立即秒杀」进入抢购页
  - 订单列表和后台订单标记「秒杀」
- 秒杀查询接口公开，下单接口仍需登录

**方案说明**

- 本片仍是同步建单，Kafka 异步在 S3
- 取消后不恢复购买资格，避免同一用户反复占用活动库存；如果要允许重新抢，需要额外规则
- 每人限购固定 1 件（接口和页面都限制）

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 111 个用例全部通过（新增 10 个抢购用例）
  - 抢购扣 Redis 与 DB 库存、重复购买返回原订单
  - 售罄不超卖、6 人并发抢 3 件只成功 3 单
  - 实物缺地址拒绝且不扣库存
  - DB 扣库存失败时回补 Redis 并移除购买标记
  - 数字商品免地址、支付后可下载
  - 取消回补活动库存但保留购买资格
  - 未启用活动不可购买
  - Redis 丢 key 后懒重建库存和购买者
- `npm run build` 通过

**提交**

- `f056945` feat: 秒杀 Redis 抢购与同步建单

## 2026-10-24 · 秒杀 S3：Kafka 异步建单

**做了什么**

- 引入 `spring-kafka`，新增 `docker-compose.kafka.yml`（KRaft 单节点）
- 抢购链路改为异步：
  - Lua 预占成功后生成 `requestId`，投递 Kafka，接口返回 202 `PROCESSING`
  - 消费端幂等建单，结果写 Redis（`seckill:result:{requestId}`，TTL 30 分钟）
  - 前端轮询 `GET /api/seckill/results/{requestId}`，成功跳订单页，失败显示原因
- 结果状态：`PROCESSING / SUCCESS / FAILED`；只能查询自己的结果
- 失败补偿：
  - 投递失败立即回补 Redis 并返回 503
  - 消费端业务失败（售罄、商品下架、地址失效）回补 Redis、移除购买标记、记录失败原因
  - 重复消息或重复建单命中唯一索引时返回已有订单，并归还多扣的库存
- 测试用内存发布器 `RecordingSeckillOrderPublisher`，不依赖真实 Kafka；测试 profile 关闭 listener 自动启动
- Kafka 主题 `sundry.seckill.order.create`，按活动 ID 分区保证同活动消息顺序

**说明**

- 消费端不重抛异常，避免 Kafka 无限重投；失败结果直接返回给用户。瞬时 DB 故障会让本次抢购失败并回补库存，后续可加 DLT 和告警
- 结果 TTL 30 分钟，过期后用户可在订单页查看实际订单
- 本机必须启动 Kafka，否则抢购返回 503

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 113 个用例全部通过（新增 2 个异步结果与投递失败用例）
  - 抢购 202 → 消费建单 → 结果 SUCCESS
  - 重复消费不重复建单
  - 6 人并发抢 3 件只成功 3 单
  - DB 扣库存失败 → 结果 FAILED 且 Redis 回补
  - 投递失败 → 503 且 Redis 回补
  - 结果只有本人可见
- `npm run build` 通过

**提交**

- `ac8e81d` feat: 秒杀 Kafka 异步建单

## 2026-10-24 · 秒杀 S4：ZSET 超时关单

**做了什么**

- 秒杀建单成功后写入 `seckill:timeout` ZSET，score = 支付截止时间（默认 30 分钟）
- worker 每 5 秒执行：
  - Lua 原子 `ZRANGEBYSCORE` + `ZREM` 领取到期订单，多实例不会重复处理
  - 只取消仍为 `PENDING_PAYMENT` 的订单
  - 取消时回补商品库存、活动配额和 Redis 库存
  - 购买标记保留，同一用户不能重新抢
- 保留原有数据库扫描任务做兜底：两个任务都靠条件更新，重复取消不会重复回补
- 已支付订单即使到了截止时间也不会被取消
- 测试环境关闭定时任务，由测试直接调用 processor

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 118 个用例全部通过（新增 5 个超时用例）
  - 建单后进入 ZSET
  - 到期取消并回补库存、保留购买标记
  - 未到期不取消
  - 已支付不取消
  - 重复执行只回补一次

**提交**

- `c5c7a90` feat: 秒杀 ZSET 超时关单

## 2026-10-24 · 秒杀 S5：三级缓存

**做了什么**

- 引入 Caffeine，商品浏览走「本地 Caffeine → Redis → DB」
- 分类列表：本地 10 分钟，Redis 30 分钟 ± 3 分钟
- 商品详情：本地 30 秒，Redis 60 秒 ± 10 秒
- 商品列表：本地 30 秒，Redis 60 秒 ± 15 秒
- 击穿：本地分段锁 + Redis `SET NX` 互斥重建；拿不到锁时短暂回退读缓存/DB
- 穿透：商品详情不存在时缓存空值 60 秒
- 雪崩：Redis TTL 加随机抖动
- 失效：
  - 商品新增/编辑/上下架/删除/恢复：删除详情缓存、递增列表版本号、清本实例本地列表缓存
  - 列表 key 带版本号，旧版本 key 不再读取，靠 TTL 自然过期
- Redis 异常时降级读 DB，不阻断商品浏览
- 库存展示允许最多旧 60 秒；下单/秒杀仍以 DB 和 Redis 校验为准

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 124 个用例全部通过（新增 6 个缓存用例）
  - 详情命中缓存、直接改库仍读旧值、evict 后读到新值
  - 后台更新后详情和列表同时失效
  - 不存在的商品缓存空值，evict 后可读到新插入数据
  - 新建商品后列表版本号变化
  - 分类列表缓存
  - 10 个并发详情请求全部成功
- `npm run build` 未改前端

**提交**

- `b94c18a` feat: 商品浏览三级缓存

## 2026-10-24 · 缓存细化：列表 ID + 卡片独立

**做了什么**

- 商品列表缓存从「整页快照」改成两层：
  - `cache:product-ids:{version}:{categoryId}:{page}:{size}`：商品 ID 序列 + 总数
  - 卡片继续走商品详情缓存 `cache:product:detail:{id}`
- 普通编辑（名称、描述、价格、封面、库存）只失效该商品卡片，**不升级列表版本**
- 只有改变列表成员/归属的操作才升级版本：
  - 新增、删除、恢复、上下架
  - 修改商品分类
- 组装列表时按 ID 逐个取卡片，缺卡（下架/删除）时跳过；版本升级会先让 ID 页失效
- 失效粒度从「全站列表」缩小到「单张卡片」，热门列表不需要因为一个商品改名而全量重建

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 126 个用例全部通过（缓存用例增至 8 个）
  - 列表同时生成 ID 页 key 和卡片 key
  - 商品改名后列表版本不变，但列表内容已更新
  - 更换分类会升级列表版本
  - 原有详情、空值、分类、并发用例保持通过
- `npm run build` 未改前端

**提交**

- `b90743e` refactor: 商品列表缓存改为 ID 页 + 卡片独立缓存

## 2026-10-24 · 秒杀 S6：限流与可观测性

**做了什么**

- 秒杀提交按用户限流：Redis 固定窗口，默认每秒 5 次，可配置
  - 超限返回 429 `RATE_LIMITED`
  - Redis 异常时放行，避免限流组件拖垮抢购
  - 限流在活动校验之前，保护无效请求洪峰
- Micrometer 指标：
  - `sundry.seckill.submit{result=...}`：accepted / existing / RATE_LIMITED / SECKILL_SOLD_OUT / SECKILL_LIMIT_REACHED / SECKILL_NOT_READY 等
  - `sundry.seckill.order{result=success|failed, reason=...}`
  - `sundry.seckill.compensation`：回补次数
  - `sundry.seckill.submit.duration`：提交耗时
- 新增 `micrometer-registry-prometheus`，暴露 `/actuator/prometheus`
- 测试 profile：限流窗口 60 秒、上限 2 次，保证用例稳定

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 129 个用例全部通过（新增 3 个限流/指标用例）
  - 第 3 次抢购请求返回 429
  - 成功建单计数、补偿计数
  - `/actuator/prometheus` 可读取秒杀指标
- `npm run build` 未改前端

**提交**

- `6d98312` feat: 秒杀用户限流与 Prometheus 指标

## 2026-10-24 · 秒杀 S7：对账与压测

**做了什么**

- 对账服务：
  - 对比 `sold_count` 与有效订单数（排除 CANCELLED / REFUNDED）
  - 对比 Redis 剩余库存与 DB 剩余库存（`stock - sold_count`）
  - 默认只报告，写 `sundry.seckill.reconcile.mismatch` 指标并记录日志
  - `repair=true` 且 `sold_count` 一致时，按 DB 重建 Redis 库存；已售数不一致时拒绝自动修复
- 后台接口：`POST /api/admin/seckill/activities/{id}/reconcile?repair=true`
- 每天 4:30 对进行中的活动做一次只读对账
- 压测脚本：
  - `loadtest/browse.js`：商品列表 + 详情 + 分类并发读
  - `loadtest/seckill.js`：多用户并发抢购，支持 VUS / ITERATIONS
  - `loadtest/README.md`：环境准备、活动准备、结果校验 SQL 和 Prometheus 指标

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 131 个用例全部通过（新增 2 个对账用例）
  - 一致时报告正常
  - 人为制造 Redis 库存漂移后能识别，repair 后恢复
  - 对账接口仅管理员可用
- 压测脚本未在 CI 中执行，需本机启动完整环境后手动跑

**提交**

- `34a0e13` feat: 秒杀对账与 k6 压测脚本

## 2026-10-24 · 秒杀压测实测

**做了什么**

- 修正 Boot 4 Kafka 依赖：`spring-kafka` 换成 `spring-boot-starter-kafka`
  - 之前本地启动时报 `KafkaTemplate` 不存在，测试 profile 关掉了 listener 所以没暴露
- 新增 `loadtest/prepare.mjs`：
  - 超管登录、上传 1KB 数字文件、创建数字商品和活动
  - 注册 1 个用户取 BCrypt 哈希，批量插入 199 个测试用户
  - 逐个登录生成 `tokens.txt`，活动 ID 写入 `activity.txt`
- k6 阈值修正：409 售罄、429 限流属于预期结果，改用 `seckill_unexpected_status == 0` 判定

**实测结果**

环境：Windows 11 单机，MySQL / Redis / MinIO / Kafka（Docker）+ 后端本地进程。
场景：200 个用户并发抢 50 件数字商品，每人 1 次。

```text
第二轮（Kafka producer 已预热）：
200 requests，50 accepted，150 sold out
p99 = 139ms
unexpected status = 0
短时吞吐约 2000 req/s
```

一致性：

```text
seckill_activity: stock=50, sold_count=50
orders: 50 行，50 个不同 user_id
redis seckill:stock = 0
redis seckill:buyer HLEN = 50
sundry_seckill_compensation_total = 0
```

第一轮 p99 约 1.16s，原因是 Kafka producer 首次初始化；预热后恢复。

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 131 个用例全部通过
- 压测实测完成，零超卖、零重复、零补偿

**提交**

- `28b57bb` fix: 使用 spring-boot-starter-kafka 启用自动配置
- `5233039` test: 秒杀压测脚本与实测

## 2026-10-24 · 分类管理

**做了什么**

- 后台分类 CRUD：
  - `GET /api/admin/categories`：全部分类（含停用）+ 商品引用数
  - `POST` 新增、`PUT /{id}` 改名/排序/状态、`DELETE /{id}`
- 校验：名称必填 ≤50 且唯一；排序值非负；状态 0/1
- 删除保护：分类下有商品时返回 409 `CATEGORY_IN_USE`，提示先迁移或停用
- 停用只影响前台展示，不迁移商品，商品保留原分类
- 公开 `GET /api/categories` 只返回启用分类，按 `sort_order, id` 排序
- 分类变更后清分类缓存，前台立即生效
- 前端新增 `/admin/categories`：列表、新增、编辑、启用/停用、删除
- 所有后台页面导航增加「分类管理」
- 不需要新迁移，复用现有 `category` 表

**说明**

- 商品卡片缓存里的 `categoryName` 最多旧 60 秒；分类改名后详情/列表卡片会短暂显示旧名
- 暂不做父子分类和拖拽排序

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 136 个用例全部通过（新增 5 个分类管理用例）
  - 新增后公开列表立即可见（缓存失效）
  - 停用后前台不可见、后台仍可见
  - 重名拒绝
  - 有商品引用不能删除，无引用可删除
  - 普通用户无权访问管理接口
- `npm run build` 通过

**提交**

- `ecba839` feat: 分类管理

## 2026-10-24 · 商品详情管理员操作

**做了什么**

- 管理员/超管打开商品详情时，不再显示买家操作（数量、加购、去购物车、秒杀抢购）
- 改为管理员操作：
  - 编辑商品：跳转 `/admin/products?edit={id}`，后台自动打开编辑表单
  - 下载数字文件：新增 `GET /api/admin/products/{id}/download`，返回 10 分钟预签名地址
  - 上架/下架
  - 删除商品（软删除，进回收站）
- 商品详情显示当前状态标签
- 普通用户看到的行为不变

**说明**

- 管理员下载不需要下单，直接按商品绑定的数字文件签发地址
- 实物商品调用下载接口返回 400 `PRODUCT_DOWNLOAD_NOT_SUPPORTED`
- 普通用户调用管理端下载接口返回 403

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 137 个用例全部通过
  - 管理员下载数字商品文件成功
  - 普通用户 403
  - 实物商品 400
- `npm run build` 通过

**提交**

- `401268c` feat: 商品详情管理员操作

## 2026-10-24 · 商品列表滑动分页（keyset）

**做了什么**

- V16 增加索引：
  - `(status, deleted_at, id)`
  - `(status, deleted_at, category_id, id)`
- 新增 `GET /api/products/scroll?categoryId=&cursor=&size=`
  - 按 `id` 倒序，`id < cursor`
  - 多取一条判断 `hasMore`
  - 返回 `{ items, nextCursor, hasMore }`
  - 不传 cursor 取第一页
- 后端直查 DB，不新增 Redis 结构；商品卡片继续复用详情三级缓存
- 旧的 `GET /api/products?page=&size=` 保留，后台分页不受影响
- 前端商品列表改为滑动加载：
  - `IntersectionObserver` 提前预加载
  - 按商品 id 去重
  - 分类切换重置游标
  - 请求序号防止旧分类结果覆盖新分类
  - 保留「加载更多」按钮作为兜底
  - 底部显示加载中 / 没有更多

**说明**

- 当前排序是 `id DESC`；按销量/价格排序时需要复合游标，后续再做
- 深层游标暂不缓存，避免 Redis 被无限游标撑大
- 第一版没有改 Feed，只是普通商品列表的滑动分页

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 142 个用例全部通过（新增 5 个游标分页用例）
  - 三页共 25 条无重复、无遗漏
  - 分类过滤正确
  - 空分类
  - 非法 cursor 400
  - 旧 page 接口仍可用
- `npm run build` 通过
- 本机实测：82 个商品，第一页 12 条，第二页游标 78 继续返回 12 条，索引已生效

**提交**

- `c29d175` feat: 商品列表 keyset 滑动分页

## 2026-10-24 · 滑动列表返回保持位置

**做了什么**

- 新增 Pinia `catalog` store，保存分类、商品、游标、hasMore 和滚动位置
- 商品列表从详情页返回时：
  - 不重新请求第一页
  - 恢复之前的商品和滚动位置
- 切换分类时重置列表并回到顶部
- 滚动位置用 `requestAnimationFrame` 节流保存，组件卸载时再保存一次
- 浏览器整页刷新仍会重新加载（内存状态不持久化）

**验证**

- `npm run build` 通过
- 后端测试未改，仍是 142 个

**提交**

- `4f348c8` fix: 商品列表返回时保留数据与滚动位置

## 2026-10-24 · 首页智能 Feed（F1）

**做了什么**

- V17 新增 `user_behavior`，记录商品浏览：
  - `POST /api/behaviors/view`
  - 同一用户同一商品 5 分钟内 Redis 去重
  - 行为写入后删除该用户的 Feed 快照
- Feed 接口 `GET /api/feed?cursor=&size=`
  - 未登录返回匿名 Feed，登录后带个人偏好
  - 游标是 Redis 快照列表的下标
- 规则打分：
  - 50% 分类偏好（最近 30 天浏览 + 购买，购买权重 ×3）
  - 25% 近 30 天销量
  - 15% 新鲜度（90 天衰减）
  - 10% 稳定随机
  - 低偏好分类探索加分
- 过滤：已购买、下架、删除、实物缺货
- 多样性与探索：
  - 每 12 个一块，同一分类最多 5 个
  - 同一分类最多连续 2 个
- 热销数据直接用订单聚合，不新增统计表
- 结果写入 `feed:home:{userId|anon}` Redis List，TTL 5 分钟
- 前端：
  - `/` 改为推荐 Feed，无限滑动，复用滚动位置保持
  - 原分类浏览移到 `/products`
  - 顶部导航新增「推荐」「商品」
  - 商品详情对登录用户上报浏览行为
  - 抽出 `ProductGrid` 组件复用商品卡片

**方案说明**

- F1 是单路候选 + 规则排序，不做多路召回、不用 LLM
- 浏览数据少时效果接近热销 + 新品 + 分类混排
- 快照 5 分钟内不重复计算；新浏览会主动失效快照
- 当前候选上限 500，商品量再大需要多路召回

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 147 个用例全部通过（新增 5 个 Feed 用例）
  - 匿名两页 20 条无重复，分类混排
  - 浏览某分类后该分类商品进入前 5
  - 已购商品被排除
  - 实物缺货被排除
  - 浏览去重、未登录上报 401
- `npm run build` 通过
- 本机实测：匿名 Feed 12 条混排 4 个分类；登录用户浏览软件后，软件商品排到前二

**提交**

- `576bee6` feat: 首页智能 Feed（规则版）

## 2026-10-24 · Feed 稳定性与行为异步落库

**做了什么**

- Feed 快照不再因为浏览行为而立即失效
  - 用户从详情返回后，当前列表和游标保持稳定
  - 只有整页刷新/首次进入首页才重新计算
- `/api/feed` 增加 `refresh` 参数
  - 前端首次加载传 `refresh=true`
  - 加载更多不传，继续使用同一份快照
- 浏览行为改为异步落库：
  - Redis 5 分钟去重
  - 写 `behavior:queue` 后立即返回
  - 每 2 秒批量落库，单批默认 500
  - `behavior:processing` 保留到落库成功，失败下次重试
  - `SETNX` 锁避免多实例重复 flush
  - V18 增加 `event_id` 唯一索引，重试时 `INSERT IGNORE` 幂等
  - Redis 不可用时降级为直接写库

**方案说明**

- 请求链路不再同步写 MySQL，峰值时只写 Redis
- Feed 快照和浏览行为解耦，避免“看一眼商品列表就变”
- 行为最多延迟 2 秒进入数据库，对推荐权重影响可接受

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 148 个用例全部通过
  - 浏览后当前快照不变
  - `refresh=true` 按新偏好重算
  - 行为先入队，flush 后落库
  - 去重、已购排除、缺货过滤保持通过
- `npm run build` 通过
- 本机实测：浏览接口 204，约 2 秒后 `user_behavior` 落库

**提交**

- `02a0ab1` feat: Feed 快照稳定与行为异步落库

## 2026-10-24 · Feed 偏好窗口与行为清理

**做了什么**

- 分类偏好计算缩小窗口：
  - 只看最近 **14 天**、最近 **100 次**浏览
  - 最近 **10 笔订单**，购买权重 ×3
- 不再统计 30 天全部浏览，减少聚合扫描量，近期兴趣更明显
- V19 增加 `(event_type, created_at)` 索引
- 浏览明细保留 30 天：
  - 每天 4 点清理 `VIEW` 且超过 30 天的记录
  - 每批 5000 条，最多 10 批，避免长事务
  - 测试环境关闭定时任务
- 配置项：
  - `app.feed.behavior-retention-days`
  - `app.feed.behavior-cleanup-batch-size`
  - `app.feed.behavior-cleanup-max-batches`

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 150 个用例全部通过（新增 2 个用例）
  - 20 天前的浏览不参与偏好计算
  - 31 天前的浏览明细被清理，新记录保留
  - 原有 Feed、异步落库、去重用例保持通过
- 前端未改

**提交**

- `daf5e6b` feat: Feed 偏好窗口收敛与行为清理

## 2026-10-24 · AI 智能推流（异步 LLM 重排）

**做了什么**

- 新增 `ai/` Python 服务，`POST /internal/feed/rerank`：
  - DeepSeek（OpenAI 兼容）JSON 模式，`temperature=0.3`
  - `X-Internal-Token` 内部令牌，密钥只放 Python 环境变量
  - 只接受 Java 下发的候选 ID，越界和重复 ID 丢弃
  - 提示词版本 `feed_rerank_v1`
- Java 异步链路：
  - `refresh=true` 生成 buildId、写 pending、入 Redis 队列，请求立即返回规则排序 + `PENDING`
  - `AiFeedBuildJob` 每秒消费，`RPOPLPUSH` 到 processing，失败可重试
  - 用户锁 + buildId 二次校验，构建期间的旧结果不会覆盖新请求
  - 失败最多重试 2 次，之后清 pending 降级
  - V20 `ai_feed_log` 记录模型、候选数、返回数、耗时、状态
- Feed 响应新增 `aiStatus` / `aiBuildId`；商品 `reason` 字段保留但前端不展示
- 前端：换一批 → 轮询 3 次（1.5 秒）→ 拿到 READY 静默替换列表
- LLM 输出收敛为 `{"items":[{"id":...}]}`，要求返回指定数量（默认 20），不再生成理由
- 匿名用户、AI 未配置或关闭时，行为与之前完全一致

**方案说明**

- LLM 只做重排，不做召回；候选 Top60 仍由规则产出
- 不是每次请求都调 LLM：每个用户每次"换一批"最多一次，快照缓存 10 分钟
- Python 不连数据库、不持有用户 JWT、不做写操作
- LLM 不可用时首页照常返回规则排序

**遇到问题**

- 冒烟测试时中文 JSON 直接写在命令行里会编码错误，改为 UTF-8 文件 + `--data-binary @file`
- 异步化后 `refresh=true` 不再同步返回新排序；测试改为 AI 关闭时保持原行为，AI 开启用单独用例验证
- 本地联调时后端没有配置 `AI_INTERNAL_TOKEN`，`FeedService` 直接降级，表现为"换一批没反应"；令牌已写进 `application-local.yml`（gitignored）
- Java 的 `RestClient` 直接发送对象会使用 `Transfer-Encoding: chunked`，本地 uvicorn 收不到完整请求，报 `content type [application/octet-stream]`。改为先用 `ObjectMapper` 序列化成 String 再发送，固定 `Content-Length`
- 60 个候选的 LLM 调用实测 4~11 秒，原来的 5 秒读超时让 Java 先断开连接，uvicorn 的 200 响应到达时客户端已经关闭。`app.ai.timeout` 调到 30 秒，前端轮询改为 10 次（15 秒）
- 60 个候选下模型偶发非法 JSON；温度降到 0.2、`max_tokens` 提到 2500，并在 Python 侧记录非法输出原文，便于排查
- DeepSeek 偶发返回空 content（日志里 `invalid llm output:` 后为空）；Python 内部重试一次，仍为空返回 503，由 Java worker 再重试/降级
- 审计里的 `returned_count` 改为模型实际返回数量，不再用合并后的快照长度

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 157 个用例全部通过（新增 7 个 AI 推流用例）
- `cd ai && uv run pytest -q` → 11 个用例通过
- `npm run build` 通过
- 真实 DeepSeek 调用：只返回候选内 ID，顺序为 74 → 362 → 70 → 85 → 19，`promptVersion=feed_rerank_v1`
- 端到端实测（Hazel）：`PENDING` → 约 11.5 秒后 `READY`，`returned_count=20`；首屏顺序由
  `354, 269, 268, 88, 19, ...` 变为 `85, 354, 88, 269, 276, ...`
- 未启动 Python 服务时：接口返回 `FALLBACK`，列表仍可用

**提交**

- `c00976f` feat: AI 智能推流（异步 LLM 重排）
- `1a60761` docs: 更新开发日志（AI 智能推流）
- 后续：输出收敛为 ID、前端去掉理由展示

## 2026-10-25 · Feed 曝光与点击回流

**做了什么**

- V21 新增 `feed_event`：
  - `trace_id / user_id / event_type / algorithm / build_id / product_id / category_id / item_position / created_at`
  - 唯一键 `(trace_id, user_id, event_type, product_id, item_position)`，`INSERT IGNORE` 幂等
- `POST /api/feed/events`（登录）批量上报，单次最多 100 条：
  - `user_id` 只来自 JWT，请求体里的 userId 被忽略
  - `category_id` 服务端按商品补全；未知/已删除商品直接忽略
  - 支持 `AI / RULE` 标记，`build_id` 可空
- Feed 响应新增 `feedBuildId`：表示**实际展示**的那份快照，和正在计算的 `aiBuildId` 区分
- 前端：
  - 每次列表渲染生成 `traceId`
  - 曝光按新增商品增量上报，同一 trace 内去重
  - 点击用 keepalive 上报，跳转前也能发出
  - 匿名用户不上报
- 保留期暂未加；生产按 7~14 天清理曝光，点击可保留更久

**说明**

- 曝光最初同步写 MySQL；已改为 Kafka 异步落库，见下一节
- 单次 100 条上限，前端按 100 分片
- 事件表不建外键，商品/用户删除后历史仍保留

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 165 个用例全部通过（新增 8 个）
- `npm run build` 通过
- 覆盖：正常曝光/点击、重复幂等、未登录 401、非法类型/位置 400、超 100 条 400、未知商品忽略、userId 不可伪造

**提交**

- `2076543` feat: Feed 曝光与点击回流

## 2026-10-25 · Feed 事件改 Kafka 异步 + 演示数据

**做了什么**

- 曝光/点击不再同步写 MySQL：
  - 生产环境 `KafkaFeedEventPublisher` 投递 `sundry.feed.event`，key = userId
  - `FeedEventConsumer` 消费后写 `feed_event`
  - 投递同步异常或异步失败时降级为直接写库，避免丢事件
  - 测试环境 `DirectFeedEventPublisher` 直接写库，不依赖 Kafka
- 配置：`app.feed.event-topic` / `app.feed.event-group`
- 一次性演示数据（脚本在 `target/`，已 gitignore）：
  - 300 个商品（`request_id` 以 `SIM-` 开头）
  - 20 个模拟用户
  - 600 条浏览
  - 40 个订单 / 122 个订单项
  - 3,600 条曝光 / 251 条点击，AI/RULE 交替，带位置偏置
  - 清理 SQL：`target/sim_cleanup.sql`
- 样本查询：
  - CTR：AI 7.03%，RULE 6.90%
  - 位置 CTR：1~3 位约 16~18%，9~12 位约 2%

**说明**

- 点击是模拟生成的，CTR 只用于验证查询和管线，不代表真实效果
- ClickHouse 未加：当前 MySQL 足够；数据量上来后把 consumer 的 sink 换成 ClickHouse/离线数仓即可
- 模拟订单会影响热销榜和已购排除；清理脚本可回滚

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 170 个用例全部通过（Kafka publisher 3 个 + consumer 2 个）
- `npm run build` 通过
- 主库已通过临时实例执行 V20/V21

**提交**

- `f93e748` feat: Feed 事件走 Kafka 异步落库

## 2026-10-25 · Feed 曝光/点击过期清理

**做了什么**

- 曝光保留 **14 天**、点击保留 **90 天**，均可配置
- 每天 4:30 执行，分批删除（默认 5000/批、最多 10 批），避免长事务
- 配置项：
  - `app.feed.event-exposure-retention-days`
  - `app.feed.event-click-retention-days`
  - `app.feed.event-cleanup-batch-size`
  - `app.feed.event-cleanup-max-batches`
  - `app.feed.event-cleanup-cron`
- 复用 `idx_feed_event_type_time (event_type, created_at)`
- 测试环境关闭定时任务，测试直接调用 processor

**说明**

- 清理的是分析数据，不影响用户偏好；偏好来自 `user_behavior`，仍保留 30 天
- 点击比曝光保留更久，因为点击量小、价值高
- 以后迁到 ClickHouse 时，保留策略改成 ClickHouse TTL

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 173 个用例全部通过（新增 3 个）
- 覆盖：过期曝光/点击删除、近期数据保留、分批上限

**提交**

- `a0d8fae` feat: Feed 曝光/点击过期清理

## 2026-10-25 · Feed 多路召回

**做了什么**

- 4 路召回，全部查 MySQL，不新增中间件：
  - 分类偏好：最近 14 天浏览 + 最近 10 笔订单的 Top 3 分类，取 120
  - 协同：买过同款的其他用户还买了什么，取 80
  - 热销：近 30 天销量 Top 120
  - 新品：上架时间倒序 Top 60
- 合并去重；匿名/冷启动只走热销 + 新品；不足 60 用全量兜底；上限 500
- 召回只改变候选集；规则打分、多样性、LLM 重排不变
- 配置项：`recall-hot-limit` / `recall-new-limit` / `recall-category-limit` /
  `recall-co-purchase-limit` / `recall-min-candidates` / `recall-max-candidates` /
  `recall-hot-days`
- 新增 `FeedRecallService`；`FeedService` 改用 `findForFeedByIds` 批量取详情

**说明**

- 没有上 Milvus / 向量召回；782 个商品全量 SQL 足够
- 协同查询要求"其他用户也买过锚点商品"，避免把无关商品算进来
- 偏好权重购买 ×3，窗口与现有 Feed 一致
- 召回结果按分类 → 协同 → 热销 → 新品顺序合并，超出上限时优先保留用户相关项

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 175 个用例全部通过（新增 2 个）
- 覆盖：四路都能召回、匿名只有热销+新品、合并去重
- 原有 173 个用例全部通过

**提交**

- `17ab59c` feat: Feed 多路召回（分类/协同/热销/新品）

## 2026-10-25 · Feed 离线评估

**做了什么**

- 新增 `GET /api/admin/feed/metrics?days=7`（ADMIN / SUPER_ADMIN，只读）
- 指标：
  - 整体 CTR：点击 / 曝光
  - 分算法 CTR：AI / RULE
  - 分位置 CTR
  - NDCG@12：二进制相关性，只统计有点击的 trace，无点击 trace 跳过
  - 覆盖率：曝光商品数 / 在售商品数
  - 多样性：每个 trace 的分类数均值
- 时间窗口 1~90 天，默认 7
- CTR 分母永远是曝光，不是"所有事件"
- NDCG 在 Java 里按 trace 计算，SQL 只取前 K 个位置
- 测试覆盖：已知样本精确匹配、窗口过滤、USER 403、匿名 401

**说明**

- 不做 recall@K：没有记录候选集，只有展示结果；用 CTR / NDCG 更诚实
- 当前是管理员即时查询，数据量大时应离线预计算；数据源以后可换 ClickHouse
- 合成数据里 AI 和 RULE 的 CTR 差异很小，不能据此下"AI 更好"的结论

**验证**

- `TEST_DB_PASSWORD=123456 mvn test` → 179 个用例全部通过（新增 4 个）
- 已知样本：12 曝光 / 3 点击 → CTR 0.25；AI NDCG 0.9197，RULE 0.4307，整体 0.6752

**提交**

- `ff7b284` feat: Feed 离线评估接口

## 2026-10-25 · 管理端推荐指标页

**做了什么**

- 新增 `/admin/feed`（ADMIN / SUPER_ADMIN）：
  - 曝光、点击、CTR、NDCG@12、覆盖率、多样性
  - 按算法（AI / RULE）和按位置两张表
  - 7 / 14 / 30 天窗口切换
- 管理后台导航加入口
- 复用现有 admin 样式，不引入图表库

**验证**

- `npm run build` 通过
- 后端接口已有 4 个集成测试覆盖；前端只做展示

**提交**

- `6283d2a` feat: 管理端推荐指标页

## 2026-10-25 · 语义召回（Milvus + 百炼 embedding）

**做了什么**

- Milvus 2.5.4 单机（etcd + 独立 MinIO）：
  - 端口：19530 / 9091；MinIO 9100 / 9101，避开业务 MinIO
  - 本地镜像：milvusdb/milvus:v2.5.4、etcd:v3.5.14、minio RELEASE.2024-05-10
- Python `/internal/embeddings`：
  - 百炼 OpenAI 兼容接口，`qwen3.7-text-embedding-flash`，1024 维
  - **单批最多 25 条**（厂商限制），超过直接 422
- V22 `product_embedding`：MySQL 存向量 JSON，供构造用户向量
- 管理端 `POST /api/admin/feed/embeddings/rebuild`：
  - 782 个商品，实测 **14.5 秒**
  - 同时写 MySQL 和 Milvus
- 语义召回：
  - 最近 14 天浏览（权重 1）+ 最近 10 笔购买（权重 3）
  - 商品向量加权平均 → 归一化 → Milvus ANN → TopN
  - 作为第 5 路接入 `FeedRecallService`
- Milvus 不可用、没有向量或没有历史时返回空，其他召回路线不受影响
- 测试环境 `app.milvus.enabled=false`，标准测试不依赖 Milvus

**验证**

- 重建 782 个商品：14.5 秒，`product_embedding` 782 行
- Milvus collection `sundry_product_vector` 创建成功
- Hazel 召回实测：total 304，category 120，hot 83，new 39，
  coPurchase 7，**semantic 55**
- `mvn test` → 185 个用例；`pytest` → 16 个用例

**说明**

- Java 走 Milvus REST，不引入 Java SDK
- 向量同时存 MySQL 和 Milvus：MySQL 用来算用户向量，Milvus 用来 ANN
- 商品上下架/改标题后需要重新 rebuild；后续可加变更触发
- Milvus MinIO 使用默认 `minioadmin`，仅本地开发

**提交**

- `c28f6d6` feat: 语义召回（Milvus + 百炼 embedding）

## 2026-10-25 · 向量增量更新与商品标签

**做了什么**

- 向量增量：
  - 商品新增/编辑/上下架/删除后，异步刷新该商品向量
  - 每 5 分钟扫描缺失/过期向量兜底
  - 下架/删除商品清理 MySQL + Milvus 向量；Milvus 失败保留 MySQL 行，下轮重试
  - 全量重建接口保留
- 商品标签：
  - V23 `product.tags`，逗号分隔，自由输入，最多 8 个、每个 ≤20 字
  - 后台商品表单支持标签；服务端 trim、去重、校验
  - 商品详情展示标签；embedding 文本包含标签
  - 标签偏好：最近浏览 ×1 + 购买 ×3，取商品标签权重
  - 规则精排新增 `tagMatch`，权重调整：
    `0.40 分类 + 0.20 热度 + 0.15 新鲜度 + 0.10 标签 + 0.05 随机 + 探索`
- 主库已迁移 V23，789 个商品按分类回填标签

**验证**

- `mvn test` → 192 个用例（新增 4 个向量用例 + 3 个标签用例）
- `npm run build` 通过
- 标签回填：789 个商品
- 原有 185 个用例保持通过

**说明**

- 标签用逗号分隔存在 `product.tags`，当前规模不需要关联表
- 标签变更会更新 `updated_at`，向量同步任务会自动重算
- 价格偏好按你的要求暂不做

**提交**

- `6c7d65f` feat: 商品向量增量更新与过期清理
- `7acf9f5` feat: 商品标签与标签偏好排序

## 2026-10-25 · AI 推流变体池（切片 1：存储层）

**做了什么**

- 设计文档 `docs/FEED_VARIANT_POOL.md` 评审通过，确认：变体数 2、策略 `PREF`/`EXPLORE`/`PRICE`、TTL 20 分钟、已曝光商品下沉、保留首次访问轮询；额外确认补齐 A/B/C/D 四类构建触发点
- `AiFeedStore` 新增环形池 `feed:ai:pool:{uid}`（LIST）和 `feed:ai:pool:seen:{uid}`（ZSET，TTL 1 小时）
- Lua 原子切换：`LPOP` 池头 + `RPUSH` 池尾，并发下每个请求拿到不同变体
- `switchVariant` 跳过并按值移除过期/损坏变体，命中后把商品 id 写入 `pool:seen`
- 新增配置 `app.ai.feed-variant-ttl`，默认 20m
- 旧快照 `feed:ai:snapshot:{uid}` 和全部旧方法保持不变，现有读取路径未改

**遇到问题**

- 环形池长度不随切换下降，设计文档 §4.5 的「剩余 = LLEN(pool)」不能作为补算水位；补算触发点改为「未过期变体数不足」，留到切片 3 落地

**验证**

- 新增 `AiFeedStorePoolTest` 5 个用例：环形顺序与回绕、过期跳过并移除、全部过期返回空池、已曝光记录、20 次并发切换各取到 10 次（Lua 原子性）
- `TEST_DB_PASSWORD=123456 mvn test` → 197 个用例全部通过（原 192 + 5）

**提交**

- 待提交：`feat: AI 推流变体池存储层`

## 2026-10-25 · AI 推流变体池（切片 2：多变体构建）

**做了什么**

- 设计调整：新增 `feed:ai:pool:next:{uid}` 待上架区；新一批变体先写这里，用户下次点击时才原子提升为正式池，避免“用户没点、列表自己变”
- 补算触发点明确为：首次访问、切满一轮、可用变体不足、全部过期、失败熔断（`docs/FEED_VARIANT_POOL.md` §4.5、§12）
- `FeedSnapshot` 增加 `strategy`，`RerankRequest` 增加 `strategy`，均保留旧构造器兼容现有调用
- `AiFeedBuildProcessor` 一次构建 N 份变体：`CompletableFuture` + 专用线程池并发调用，无新依赖；部分变体失败时成功的那份照常写入
- 策略分配：`PREF` 固定第一份，其余按用户奇偶在 `EXPLORE` / `PRICE` 之间轮换，保证三套策略都有样本
- 变体 `buildId` 改为 `{STRATEGY}:{UUID}`；头部去重默认前 6 个，冲突项与后续候选交换
- 构建结果写 `pool:next`；旧快照暂时保留，读取路径不变（切片 3 删除）
- Python：`RerankRequest.strategy` + system prompt 末尾追加「附加偏好」，原有“只能返回候选 id”“只输出 JSON”规则保持不变

**遇到问题**

- `pool:next` 的 Lua 参数走 `StringRedisSerializer`，TTL 传 `Long` 触发类型转换失败，构建整体降级；改为字符串后恢复

**验证**

- Java：200 个用例全部通过（新增 3 个：双策略入待上架区、头部去重、部分变体失败）
- Python：18 个用例全部通过（新增 2 个：策略段落不影响规则、未知策略仍校验 id）
- `npm run build` 通过

**提交**

- 待提交：`feat: AI 推流多变体并发构建`

## 2026-10-25 · AI 推流变体池（切片 3：读取切池子）

**做了什么**

- `FeedService.aiPage`：点击「换一批」改为「待上架区有新鲜批次则提升 → Lua 切一份」，毫秒返回，不再入队等模型
- 普通读取按 `buildId` 固定变体，不推进指针；新增 `feed:ai:pool:current:{uid}` 记录当前展示变体，页面重载（无 buildId）返回同一份
- 池空 / 变体全过期 → 规则页 + `PENDING` + 补算；补算失败熔断 → `FALLBACK`
- 补算触发点全部落地：首次访问、可用变体不足、切满一轮（`pool:round` 计数）、全部过期
- `PENDING` 时 `aiBuildId` 为 null；`READY` 时返回当前变体 buildId
- 构建成功不再写旧快照；失败时保留 `attempt` 作为 5 分钟熔断标记，避免每次读取都重试
- 前端：首次进入 `PENDING` 也轮询，`READY` 直接采用新变体并按新 buildId 继续翻页

**遇到问题**

- 环形池切换后池头变成下一份，页面重载（前端拿不到 buildId）会换榜；新增 `pool:current` 指针修正
- 构建失败后 `pending` 被清除，读取会立刻重新入队；改为保留 attempt 计数做熔断

**验证**

- Java：207 个用例全部通过（新增 2 个存储用例、5 个读取路径用例：切换不等构建、点击前不替换、空池只入队一次、普通读取触发构建、过期回退）
- Python：18 个用例通过
- `npm run build` 通过

**提交**

- 待提交：`feat: AI 推流读取切换到变体池`

## 2026-10-25 · AI 推流变体池（切片 4：复用变体曝光下沉）

**做了什么**

- 复用旧变体时，把最近 10 分钟曝光过、且位于前 8 名的商品下沉到第 12~20 位，不删除、列表长度不变
- 下沉后的顺序写入 `feed:ai:variant:view:{uid}:{buildId}`（TTL 与变体一致）；翻页读取同一份，避免第一页下沉、第二页没下沉
- 前端轮询与埋点在切片 3 已符合要求：只在 `PENDING` 时轮询，`feedBuildId` 直接作为变体 ID 上报，事件接口未改

**遇到问题**

- 首版下沉循环用“当前位置”判断前 8 名，遇到整份变体都已被记录为曝光时会一直删除到列表末尾；改为独立计数前 8 个原始位置后修复

**验证**

- Java：209 个用例全部通过（新增 2 个：集成层三次切换后头部下沉且翻页不重复、存储层下沉位置断言）
- Python：18 个用例通过
- `npm run build` 通过

**提交**

- 待提交：`feat: AI 推流复用变体曝光下沉`

## 2026-10-25 · AI 推流变体池（切片 5：策略指标）

**做了什么**

- `/api/admin/feed/metrics` 增加 `byStrategy`：按 `build_id` 冒号前缀分组，输出 PREF / EXPLORE / PRICE 的曝光、点击、CTR
- 只统计三种已知前缀，旧的纯 UUID `build_id` 排除，避免历史数据污染
- 表结构不变，复用现有 `feed_event.build_id`

**验证**

- Java：210 个用例全部通过（新增 1 个：三策略分组、CTR 计算、旧格式排除）
- Python：18 个用例通过
- `npm run build` 通过

**提交**

- 待提交：`feat: 推荐指标按变体策略分组`

## 2026-10-25 · Feed 重新打开即切变体

**做了什么**

- 无 `buildId` 的首屏请求（整站重开 / F5，`offset=0`）视为一次「换一批」：待上架区有新鲜批次先提升，否则切到池中下一份，0 等待
- 站内从其他页面返回首页仍使用前端内存状态，不发请求、不切换
- 带 `buildId` 的翻页请求保持当前变体，浏览中途不会换榜
- 触发的切换计入轮次，切满一轮后照常后台补算新批次

**验证**

- Java：211 个用例全部通过（新增“重开轮转”“翻页固定当前变体”2 个，替换原“点击前不替换”用例）
- Python：18 个用例通过
- `npm run build` 通过

**提交**

- 待提交：`feat: 首页重开切换下一份变体`

## 2026-10-25 · Feed 变体池审查修复

**做了什么**

- 已曝光记录只写本次实际返回的那一页商品，不再把整份变体（约 60 个）都标记为曝光；下沉逻辑据此更准确
- prompt 版本升到 `feed_rerank_v2`，prompt 文件同步重命名，审计和指标可以区分新老版本
- 删除旧快照读写（`snapshot` / `saveSnapshot` / 快照 key）和无用的 `feed-cache-ttl` 配置

**验证**

- Java：211 个用例全部通过（曝光记录用例改为只记返回商品）
- Python：18 个用例通过
- `npm run build` 通过

**提交**

- 待提交：`fix: Feed 变体池审查修复`

## 2026-10-25 · 策略指标排除 RULE 事件

**做了什么**

- `byStrategy` 查询增加 `algorithm = 'AI'`，避免状态切换瞬间的 RULE 事件被算进策略指标

**验证**

- Java：210 个用例通过（指标用例新增“RULE 事件带 PREF buildId 不计入”断言）

**提交**

- 待提交：`fix: 策略指标排除 RULE 事件`

## 待办 / 下一步

完整清单见 [docs/BACKLOG.md](docs/BACKLOG.md)，设计细节见 [docs/FUTURE_DESIGN.md](docs/FUTURE_DESIGN.md)。

- 已完成：AI 智能推流、曝光/点击 Kafka 异步回流、演示数据、过期清理、多路召回、离线评估、管理端指标页、语义召回、向量增量、商品标签、方案 B（换一批即时变体）
- 下一步：精排模型（需真实数据），或按原规划继续 A1/A4/A5/A7
- 之后：A4 助手框架 → A5 NL2SQL 安全 → A7 安全评测 → A3 推荐
- 上线前：真实短信/支付、HTTPS、静态托管、CI、备份

## 已知风险 / 上线前必须处理

- 短信仍是日志通道，生产需接阿里云 / 腾讯云并配置签名和模板
- JWT 密钥、数据库密码、MinIO 凭据在本机 `application-local.yml`（已 gitignore），生产走环境变量或密钥管理
- `refreshCookieSecure=false` 仅适合 HTTP 开发；生产 HTTPS 必须设为 `true`
- 前端构建产物尚未接入 Spring 静态托管，生产部署方案未定
- 无 CI；测试依赖本机 MySQL 和 Redis
- 业务前置：支付商户号、ICP 备案、经营主体尚未确认
- 分类目前只有迁移种子，后台还不能增删改
- 数字商品下载已接通：订单支付后签发 10 分钟预签名地址；退款后禁止下载
- 清理任务已加订单引用保护，被订单引用的数字文件不会删除
- 支付目前是模拟支付；真实微信/支付宝需要商户资质，接入前不能算真实收款
- 退款目前只是状态流转和库存回补，没有真实资金退回；接真实渠道时要补退款单、回调幂等和失败重试
- 商品删除是软删除，已有回收站可恢复；但没有物理清理策略，数据会一直保留

---

## 新条目模板

```markdown
## YYYY-MM-DD · 切片名

**做了什么**

- 

**遇到问题**

- 

**解决**

- 

**验证**

- 

**提交**

- `hash` type: message

---
```
