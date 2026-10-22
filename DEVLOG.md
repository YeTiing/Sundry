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
| 测试 | 10 个集成测试，`mvn test` 全绿 |
| 分支 | `develop`、`main` 均在 `7b1a0b1`，`origin/main` 已同步 |
| 未完成 | 商品、SKU、库存、购物车、订单、支付、发货、售后、通知公告 |

### 本地启动

1. MySQL 3306、Redis 6379 在跑（Redis 容器没起时：`docker start sundry-redis`）
2. IDEA：JDK 17，Active profiles 填 `local`，或 VM options 加 `-Dspring.profiles.active=local`
3. 运行 `SundryApplication`，健康检查 `http://localhost:8080/actuator/health`
4. 前端：`cd web && npm install && npm run dev`，访问 `http://localhost:5173`
5. 测试：`TEST_DB_PASSWORD=123456 mvn test`

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

## 待办 / 下一步

- 商品与 SKU、库存（条件更新防超卖）
- 购物车、下单、订单状态机
- 支付渠道接入（商户资质是前置）
- 发货、确认收货、退款售后
- 站内通知、公告已读

## 已知风险 / 上线前必须处理

- 短信仍是日志通道，生产需接阿里云 / 腾讯云并配置签名和模板
- JWT 密钥、数据库密码在本机 `application-local.yml`（已 gitignore），生产走环境变量或密钥管理
- `refreshCookieSecure=false` 仅适合 HTTP 开发；生产 HTTPS 必须设为 `true`
- 前端构建产物尚未接入 Spring 静态托管，生产部署方案未定
- 无 CI；测试依赖本机 MySQL 和 Redis
- 业务前置：支付商户号、ICP 备案、经营主体尚未确认

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
