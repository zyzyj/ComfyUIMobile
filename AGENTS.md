# 项目规则 · ComfyUIMobile

> 每次会话自动注入。只写硬约束与不可变事实。

## 1. 工作区边界

```
/storage/emulated/0/Download/ai项目/项目六/
```

容器内：`~/workspace/`

- **本项目就是工作区根本身**，不在子目录
- 同级的 `其他项目（这与项目六无关）/`（laten出图、墨坊）与 `liumoapp/` **一律不管、不提交 GitHub**
- 提交前必须确认改动范围只在本项目内

## 2. 工程

**ComfyUIMobile** —— Android 应用（Kotlin + Gradle）

- 包名 `com.local.comfyuimobile`
- JDK 17 + Android SDK 36
- 仓库：`https://github.com/zyzyj/ComfyUIMobile`

**构建方式：推 dev 分支走 GitHub Actions CI，不要在本地构建。**

```bash
git push origin dev
```

产物由 CI 产出 `ComfyUIMobile-vX.Y.Z-debug.apk`。
Release 签名配置在仓库外 `../tools/signing/`，密钥与密码**永不提交**。

**版本号纪律**：每次改动 +0.1，改 `app/build.gradle.kts` 的 `versionName` 与 `versionCode`。

## 3. 项目定位

原本是「连接 ComfyUI 提交出图」的工具。现在深度绑定**百度 AI Studio**（aistudio.baidu.com），
解决移动端的三个痛点：拿 Cookie 麻烦、浏览器保活难、终端不好用。

功能板块：账号页（登录/积分/算力卡）、控制台（终端）、ComfyUI 连接卡片、
MCP 服务页、多账号管理。

## 4. 主要源文件

```
MainViewModel                      主状态机（最大，改动要小心）
ui/ComfyMobileApp                  UI 根
data/AppPreferences                持久化
network/AiStudioProtocol           平台协议
network/AiStudioKernelClient       内核终端客户端
network/AiStudioClient             平台 API
network/ComfyClient                ComfyUI 客户端
bridge/ParameterClassifier         参数分类
bridge/ComfyBridge                 ComfyUI 桥接
mcp/McpToolRegistry                MCP 工具注册表
mcp/McpServerService               MCP 前台服务
mcp/McpServiceState                ★ 服务状态唯一真相源
mcp/ComfyMcpHost / AiStudioBridge / McpTerminalHost
service/JobMonitorService          出图任务轮询
model/TerminalCommandSafety        ★ 终端命令安全判定
model/AiStudio / Models
AiStudioLoginActivity              WebView 登录取凭证
```

## 5. ★ 已修的重大坑（别再犯）

### ① 终端 405 无限重连 —— Cookie 过期被误诊

```
GET api/terminals → 302 到登录页 → 返回 HTML
旧代码 JSONArray 解析失败 → 静默返回空列表
POST api/terminals → 落到登录页 → 405
```

**根因是登录态失效，不是接口坏。** 现在三处信号识别登录墙，
文案直说「回账号页重新登录」，重连遇它立刻停。
`enter` / `baseinfo` / `running_status` 三处都要走同一判定。

### ② MCP「假打开」三重根因

- 服务启动判据**读磁盘**，但落盘是异步的 → 读到上一次的 false 立刻自停
- `lastStopReason` 守卫不区分「系统重建」和「手动开」→ 自停一次后再也打不开
- 界面/自检/服务**三处各有一套状态** → 显示与自检必然对不上

**改法**：`McpServiceState` 单例作为唯一真相源，**删掉**被取代的
`McpServerService.isRunning()`——留着就是下一个平行路径入口。

### ③ TerminalCommandSafety 误判

- `cd /home/aistudio && rm -rf 子目录` 曾被判成「删家目录」
  根因是"整条命令再判一次"把所有段 token 塞进同一池
  **只在含 `xargs` 时才做整条判定**
- heredoc 正文（`cat > x.sh <<'EOF'` 内含 `rm -rf`）曾被拒
  **判定前剥掉正文，保留 `<<` 那一行**

## 6. 仓库卫生（★ 用户明确要求）

- **非项目文件绝不提交**：截图、诊断日志、`.aicode/attachments/`、
  `.aicode/permissions.json`、`build-errors-*.log`
- 曾因 `.gitignore` 只忽略 `.aicode/memory/`，导致 **68 个截图与日志（18.9MB，
  含真实 userId/projectId）进了 public 仓库**，已用 `git filter-repo` 从全部历史移除
- **改写历史前必须先做完整镜像备份**（不可逆）
- 强推分批进行（main、dev 分别推）
- 日志里要带**版本号**——排查时用户往往说不清对方用的哪个版本

## 7. 已知未解决 / 降级处理

- `/files/xxx` 404（6 次）：MCP 文件缓存过期，属预期
- `baseinfo 连续未返回`（11 次）：v0.3.9 已改为明说
- `Expected URL scheme`：`/userdata` 不可用时的降级，平台限制
- 强推后 GitHub 侧旧对象需等其 GC，短期内仍可能通过旧 SHA 访问
- 发布后查 Release：**用 tags 端点**，列表端点有缓存延迟会误报"没有此版本"

## 8. 方法论纪律

- **不要凭记忆断言**，先读代码或 `RELEASE_NOTES.md`（工作区根，含完整历史）
- 用户会给别人的审查意见（如"元宝审核"），**要实事求是自己分辨**，不全盘照收
- 修 bug 后要确保**不产生新 bug**
- **本版未经真机验证**这类话要如实说，不要含糊

## 9. ★ 收尾动作（每次必做）

**一个任务告一段落时（不用等用户要求），追加一条到 `工作日志.md`。**
不存在就新建，不要因读不到就跳过。

```markdown
## 2026-10-25 · MCP 状态统一

- 改了：新增 mcp/McpServiceState.kt；删除 McpServerService.isRunning()
- 结论：假打开三重根因全部消除，服务侧不再读磁盘
- 版本：0.3.10 -> 0.3.11，已推 dev
- 下一步：真机验证 MCP 开关
- 待办/疑问：通知卡片在系统重建后是否稳定
```

只写硬事实；**「没解决」或「存疑」单独一条**；不写流水账。

## 10. 上下文管理

- 一个里程碑结束就**开新会话**
- 上下文吃紧时手动 `/compress`
- 本项目会话极易膨胀（Bash 调用最多，曾到 2 万条），要主动控制

## 11. 启动动作

1. 读 `PROGRESS.md`（工作区根）——项目地图与当前进度
2. 读 `工作日志.md` 最后几条（不存在就跳过，别报错）
3. 再开始干活
