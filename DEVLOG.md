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
| 商品 | 分类（秒杀券/软件/实物）、商品 CRUD、上下架、商城列表/详情 |
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
| 指标 | Micrometer + Prometheus，抢购结果、建单、补偿、提交耗时 |
| 压测 | k6 脚本：商品浏览与秒杀抢购，附结果校验步骤 |
| 测试 | 131 个集成测试，`mvn test` 全绿 |
| 分支 | `develop`、`main` 与远端保持同步（以提交记录为准） |
| 未完成 | 真实支付、退款真实资金流、通知公告 |

### 本地启动

1. MySQL 3306、Redis 6379、MinIO 9000 在跑（容器没起时：`docker start sundry-redis sundry-minio`）
   - 秒杀功能需要 Kafka：`docker compose -f docker-compose.kafka.yml up -d`（KRaft 单节点，9092）
2. IDEA：JDK 17，Active profiles 填 `local`，或 VM options 加 `-Dspring.profiles.active=local`
3. 运行 `SundryApplication`，健康检查 `http://localhost:8080/actuator/health`
4. 前端：`cd web && npm install && npm run dev`，访问 `http://localhost:5173`
5. 测试：`TEST_DB_PASSWORD=123456 mvn test`

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

## 待办 / 下一步

- 分类管理（新增/排序/停用；目前只有迁移种子）
- 并发压测；热点 SKU/秒杀再引入 Redis（当前 DB-only）
- 真实支付渠道接入（当前模拟支付，商户资质是前置）
- 后台「未绑定文件」列表（可选，便于复用或立即清理）
- 站内通知、公告已读

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
