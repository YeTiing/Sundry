# Sundry 开发日志

记录每个开发切片：做了什么、遇到什么问题、怎么解决、怎么验证。
新条目追加在下方；顶部「当前状态」随最新进展更新。

## 当前状态（2026-10-24）

| 项 | 状态 |
|---|---|
| 后端 | Spring Boot 4.1.1 / JDK 17 / Maven 单模块 / MyBatis / Flyway |
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Vue Router；商城、详情加购、购物车、地址簿、结算、订单、退款申请、账户、后台商品页 |
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
| 名片 | 用户昵称/头像/简介；单商户店铺名片（店名/Logo/简介/联系方式） |
| 库存/购物车 | `product.stock`；购物车同商品累加、库存校验；不锁库存 |
| 地址簿 | 收货地址 CRUD、默认地址、最多 20 条 |
| 订单 | 购物车下单、条件扣库存、模拟支付、打包/发货/确认收货、30 分钟超时取消、数字下载 |
| 退款 | 用户申请（已支付/打包中且含实物）→ 商家通过/拒绝；审核前可撤销；通过后回补库存 |
| 测试 | 82 个集成测试，`mvn test` 全绿 |
| 分支 | `develop`、`main`、`origin/main` 在 `dcf6a53`；本次退款提交后 develop 领先 |
| 未完成 | 后台订单与退款审核页、真实支付、通知公告 |

### 本地启动

1. MySQL 3306、Redis 6379、MinIO 9000 在跑（容器没起时：`docker start sundry-redis sundry-minio`）
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

## 待办 / 下一步

- 分类管理（新增/排序/停用；目前只有迁移种子）
- 并发压测；热点 SKU/秒杀再引入 Redis（当前 DB-only）
- 前端：后台订单与退款审核页（打包、发货、退款通过/拒绝）
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
