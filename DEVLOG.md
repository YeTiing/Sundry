# Sundry 开发日志

记录每个开发切片：做了什么、遇到什么问题、怎么解决、怎么验证。
新条目追加在下方；顶部「当前状态」随最新进展更新。

## 当前状态（2026-10-22）

| 项 | 状态 |
|---|---|
| 后端 | Spring Boot 4.1.1 / JDK 17 / Maven 单模块 / MyBatis / Flyway |
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Vue Router |
| 数据库 | MySQL 8.0.31（本机 3306），业务库 `sundry`，测试库 `sundry_test` |
| Redis | Docker 容器 `sundry-redis`（6379，AOF + 命名卷 `sundry-redis-data`） |
| 认证 | 手机号验证码注册、验证码/密码登录、JWT + refresh 轮换、退出黑名单 |
| 权限 | SUPER_ADMIN / ADMIN / USER；超级管理员由 `app.security.super-admin-phone` 指定 |
| 存储 | MinIO `sundry-minio`（9000 API / 9001 控制台），公开桶 `sundry-public` |
| 商品图 | `POST /api/admin/files/images`，≤5MB，JPEG/PNG/WebP，随机 key |
| 商品 | 分类（秒杀券/软件/实物）、商品 CRUD、上下架、商城列表/详情 |
| 数字商品 | 私有桶、2GB 分片直传 MinIO、商品绑定 DIGITAL 文件；下载未做 |
| 测试 | 35 个集成测试，`mvn test` 全绿 |
| 分支 | `develop`、`main` 均在 `7b1a0b1`，`origin/main` 已同步 |
| 未完成 | 商品、SKU、库存、购物车、订单、支付、发货、售后、通知公告 |

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

**提交**

- `a13afe8` feat: 新增数字文件分片直传与商品绑定

---

## 待办 / 下一步

- 分类管理（新增/排序/停用；目前只有迁移种子）
- 库存扣减与并发（条件更新防超卖）
- 购物车、下单、订单状态机
- 支付渠道接入（商户资质是前置）
- 发货、确认收货、退款售后
- 数字商品下载授权（订单支付后签发短时预签名地址，允许重复下载）
- 站内通知、公告已读

## 已知风险 / 上线前必须处理

- 短信仍是日志通道，生产需接阿里云 / 腾讯云并配置签名和模板
- JWT 密钥、数据库密码、MinIO 凭据在本机 `application-local.yml`（已 gitignore），生产走环境变量或密钥管理
- `refreshCookieSecure=false` 仅适合 HTTP 开发；生产 HTTPS 必须设为 `true`
- 前端构建产物尚未接入 Spring 静态托管，生产部署方案未定
- 无 CI；测试依赖本机 MySQL 和 Redis
- 业务前置：支付商户号、ICP 备案、经营主体尚未确认
- 分类目前只有迁移种子，后台还不能增删改；商品暂无库存字段，下单前必须补
- 数字文件已能上传，但下载还没做：必须等订单和支付完成后再签发下载地址

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
