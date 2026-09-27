# dev 分支 AI Studio 集成 Bug 汇报

- 审查对象：`dev` 分支（相对 `main` 的改动），核心为百度 AI Studio 集成（v0.1.90 ~ v0.2.14）
- 审查方式：静态代码审查
- 审查范围：`app/src/main/java/com/local/comfyuimobile/` 下新增/改动文件，以及 `.github/workflows/build-apk.yml`、`AndroidManifest.xml`
- 说明：本环境未安装 JDK 17 与 Android SDK 36，**未做编译与单测验证**，以下结论均基于源码阅读。行号对应当前 `dev` 工作区。

---

## 汇总

| 编号 | 严重度 | 标题 | 位置 |
|------|--------|------|------|
| B-01 | 严重 | AI Studio 账号凭据（BDUSS/bdToken）明文落盘 | `AppPreferences.kt:210-229` |
| B-02 | 严重 | 环境 token 被写入默认开启的诊断日志 | `AppLogger.kt:45`、`AiStudioKernelClient.kt:208,237` |
| B-03 | 中等 | 补取项目级 Cookie 的重试循环不会提前退出 | `MainViewModel.kt:902-906` |
| B-04 | 中等 | 登录页 JS 求值缺少超时，可能永久卡住登录 | `AiStudioLoginActivity.kt:231-244` |
| B-05 | 中等 | CookieJar 的 cookieStore 非线程安全 | `AiStudioKernelClient.kt:56-83` |
| B-06 | 中等 | 已移除「积分任务」后残留死代码 | `AiStudioClient.kt:259-323`、`AiStudioProtocol.kt:51-56,313-320` |
| B-07 | 轻微 | 301/302 判断分支基本不可达 | `AiStudioClient.kt:367-369` |
| B-08 | 轻微 | 「已等 N 秒」计数与实际退避时长不符 | `MainViewModel.kt:1149` |
| B-09 | 轻微 | 断开终端未清空 comfyUiUrl | `MainViewModel.kt:986-996` |
| B-10 | 轻微 | 算力卡分钟数解析只接受数字 | `AiStudioProtocol.kt:451-453` |
| B-11 | 轻微 | 残留重复/悬空 KDoc | `AiStudioProtocol.kt:34-35,186-200,386-397`、`MainViewModel.kt:998-1012` |
| B-12 | 轻微 | 代码格式异常（KDoc 与函数同行、花括号错位） | `LanAddress.kt`、`ComfyMobileApp.kt:3783-3793` |
| B-13 | 轻微 | seedCookies 覆盖式写入可能丢弃 Jupyter 会话 cookie | `AiStudioKernelClient.kt:59-70` |

---

## 严重

### B-01 AI Studio 账号凭据明文落盘

**位置**：`app/src/main/java/com/local/comfyuimobile/data/AppPreferences.kt:210-229`

**现象**：`saveAiStudioAccounts()` 将每个账号的 `cookie`（核心是 BDUSS，等价于百度账号密码）和 `bdToken` 以明文 JSON 写入 DataStore：

```kotlin
.put("cookie", account.cookie)
.put("bdToken", account.bdToken)
```

**影响**：
- `AiStudioAccount` 的 Cookie 权限大于 README 中已做保护的 ComfyUI 服务器 Cookie，但没有任何加密（未使用 EncryptedSharedPreferences / Android Keystore），也未确认已从系统云备份与新机迁移中排除。
- 设备 root、备份导出、或 DataStore 文件泄露即可直接接管百度账号。

**建议方向**：改用加密存储（Keystore 派生的 EncryptedSharedPreferences/EncryptedFile），或至少加入 `android:allowBackup="false"`/备份排除规则并评估迁移。

---

### B-02 环境 token 被写入默认开启的诊断日志

**位置**：
- `app/src/main/java/com/local/comfyuimobile/data/AppLogger.kt:45`（默认值改为 `true`）
- `app/src/main/java/com/local/comfyuimobile/network/AiStudioKernelClient.kt:208`（baseinfo 原始响应，含 `token`）
- `app/src/main/java/com/local/comfyuimobile/network/AiStudioKernelClient.kt:237`（running_status_check 原始响应，含 `token`）
- `app/src/main/java/com/local/comfyuimobile/network/AiStudioClient.kt:48-50`（`logRaw` 记录响应前 300 字）

**现象**：诊断日志默认从关闭改为开启；同时 baseinfo 的响应体（含 Jupyter `token`）被原样记录：

```kotlin
val raw = client.newCall(request).execute().use { it.body?.string().orEmpty() }
AppLogger.info("环境信息响应：${raw.take(300)}")
```

**影响**：Jupyter `token` 是可直接访问云端环境的凭据。日志默认开启后，token 会持续落到 logcat / 日志文件；出问题时用户往往会把日志发给开发者。

**建议方向**：对环境连接信息做字段白名单记录（只记 `baseUrl`/`hasToken`），或对 `token`/`bdToken`/`cookie` 值统一脱敏后再落日志。

---

## 中等

### B-03 补取项目级 Cookie 的重试循环不会提前退出

**位置**：`app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:902-906`

```kotlin
var endpoint: AiStudioKernelClient.KernelEndpoint? = null
repeat(3) { attempt ->
    if (attempt > 0) delay(2_000L)
    endpoint = kernelClient.fetchEndpoint(account!!, pid, "")
    if (endpoint?.isUsable() == true) return@repeat   // 只是跳过本次迭代剩余，不跳出循环
}
```

**现象**：Kotlin 的 `return@repeat` 仅结束当前迭代，不会终止 `repeat`。因此即便第一次就拿到了可用 endpoint，仍会再 `delay(2s) + fetchEndpoint` 两轮。

**影响**：连接 ComfyUI 前固定多等约 4 秒、多发 2 次 `enter + baseinfo`（对云端反代是额外负担），与注释「重试几次再放弃」的意图相反。

**建议方向**：改为 `for (attempt in 0 until 3) { ...; if (usable) break }` 或首次可用即跳出。

---

### B-04 登录页 JS 求值缺少超时，可能永久卡住登录

**位置**：`app/src/main/java/com/local/comfyuimobile/AiStudioLoginActivity.kt:231-244`

```kotlin
/** evaluateJavascript 的协程封装：单次求值，10 秒超时兜底。 */
private suspend fun suspendCancellableCompat(script: String): String =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        runCatching { webView.evaluateJavascript(script) { encoded -> ... } }
```

**现象**：注释声称「10 秒超时兜底」，但实现里没有任何 `withTimeout`。`evaluateJavascript` 的回调在 WebView 正在销毁、页面异常等情况下可能永不触发。

**影响**：`submitLogin()` 中的协程会在 `readPageIdentity()` 处永久挂起，`finish()` 不执行 → 用户停在「登录成功，正在保存账号…」卡死，只能杀进程。

**建议方向**：用 `withTimeout(10_000) { ... }` 包装，超时返回空 `PageIdentity` 让登录继续。

---

### B-05 CookieJar 的 cookieStore 非线程安全

**位置**：`app/src/main/java/com/local/comfyuimobile/network/AiStudioKernelClient.kt:56-83`

```kotlin
private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
private var seededCookie = ""
```

`seedCookies()`（IO 线程）、`saveFromResponse()`/`loadForRequest()`（OkHttp 网络线程）、`exportCookies()`/`currentXsrf()`/`cookieNames()`（调用方线程）都会读写这个普通 `mutableMap` 与 `seededCookie`。

**影响**：并发下存在 `ConcurrentModificationException` 风险，或在终端连接期间读到半更新的 Cookie 列表，表现为偶发的「连上又断」「Cookie 不全」。

**建议方向**：换成 `ConcurrentHashMap`，并对 `seedCookies()` 整体加锁；`seededCookie` 用 `@Volatile` 或纳入同一把锁。

---

### B-06 已移除「积分任务」后残留死代码

**位置**：
- `app/src/main/java/com/local/comfyuimobile/network/AiStudioClient.kt:259-323`：`createProject` / `createVersion` / `publishProject` / `deleteProject` 已无任何调用
- `app/src/main/java/com/local/comfyuimobile/network/AiStudioProtocol.kt:51-56`：`PATH_PROJECT_ADD/PUBLIC/DELETE/VERSION_ADD`
- `app/src/main/java/com/local/comfyuimobile/network/AiStudioProtocol.kt:313-320`：`createProjectBody`

**现象**：RELEASE_NOTES（v0.2.0）明确写「去掉积分任务板块……自动建项目/公开/删除这种模拟操作风险高且经常失败，整块移除」，但客户端与协议层的相关方法仍在。

**影响**：死代码保留即保留「模拟操作」能力，误导后续维护者以为仍在用；也增加平台风控相关代码被误用的风险。

**建议方向**：删除`AiStudioClient` 中对应方法及协议常量，或明确标注 deprecated。

---

### B-07 301/302 判断分支基本不可达

**位置**：`app/src/main/java/com/local/comfyuimobile/network/AiStudioClient.kt:367-369`

```kotlin
resp.code == 302 || resp.code == 301 -> throw AiStudioException("...登录已失效...")
```

**现象**：OkHttp 默认 `followRedirects = true`，301/302 会被自动跟随，`resp.code` 不会是 301/302；最终落到登录页 HTML，由 `raw.trimStart().startsWith("<")` 分支兜住。

**影响**：重复的死逻辑，注释与行为不一致，易误导排障。功能上不致命。

**建议方向**：删除该分支，或显式 `followRedirects(false)` 后统一处理。

---

### B-08 「已等 N 秒」计数与实际退避时长不符

**位置**：`app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:1135-1149`

```kotlin
delay(if (attempt < 5) 4_000 else 8_000)
...
val waited = (attempt + 1) * 4
```

**现象**：前 5 次间隔 4s，之后间隔 8s，但展示用的 `waited` 始终按每轮 4s 计算。

**影响**：等待进度条文案偏小（例如实际等了 60 秒仍显示约 24 秒），属于误导性提示。

**建议方向**：用累计变量按实际 `delay` 值求和。

---

### B-09 断开终端未清空 comfyUiUrl

**位置**：`app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:986-996`

**现象**：`aiStudioDisconnectConsole()` 置空 `kernelEndpoint`、`consoleConnected`，但保留了 `aiStudio.comfyUiUrl`。

**影响**：断开终端后，控制台仍显示上一实例的 ComfyUI 地址与「探测/连接」按钮；实例已回收时该地址必然不可达，用户会反复点「探测」得到误导性失败。

**建议方向**：断开时一并 `comfyUiUrl = null`。

---

### B-10 算力卡分钟数解析只接受数字

**位置**：`app/src/main/java/com/local/comfyuimobile/network/AiStudioProtocol.kt:451-453`

```kotlin
listOf("resourceTotal", "resourceFree", "resourceQuota")
    .firstNotNullOfOrNull { key -> (result.opt(key) as? Number)?.toDouble() }
```

**现象**：平台若把 `resourceTotal` 返回为字符串（本仓库多处已按「平台字段不稳定」做了 string/number 双兼容），这里会直接返回 null。

**影响**：算力卡显示「—」，与「真的是 0」无法区分。与本项目既有的「缺字段只降级」原则一致但漏了字符串分支。

**建议方向**：补充 `String -> toDoubleOrNull()` 分支。

---

## 轻微（代码质量 / 可维护性）

### B-11 残留重复/悬空 KDoc

- `AiStudioProtocol.kt:34-35`：`/** 积分任务列表... */` 悬空，紧接着又是 `/** 算力卡与资源配额。 */`
- `AiStudioProtocol.kt:186-200`：`parseRunning` 上方有两段几乎相同的 KDoc
- `AiStudioProtocol.kt:386-397`：`parsePoints` 上方有两段重复 KDoc
- `MainViewModel.kt:998-1012`：`appendTerminal` 上方有两段重复 KDoc

**建议**：删除被替代的旧注释块。

### B-12 代码格式异常

- `app/src/main/java/com/local/comfyuimobile/network/LanAddress.kt`：`withoutCredentials` 的 KDoc 与 `fun` 挤在同一行：`/** 去掉认证信息后的纯地址... */    fun withoutCredentials(...)`。
- `app/src/main/java/com/local/comfyuimobile/ui/ComfyMobileApp.kt:3783-3793`：`weekQuota.forEach` 内 `Text(...)` 的闭括号与 `Row` 的 `}` 错位（`)                                }`），虽可编译但可读性差。

**建议**：按 ktlint 规则格式化。

### B-13 seedCookies 覆盖式写入可能丢弃 Jupyter 会话 cookie

**位置**：`AiStudioKernelClient.kt:59-70`

`seedCookies()` 用 `cookieStore[url.host] = cookies.toMutableList()` 整体替换该 host 的 cookie 列表，会覆盖 OkHttp `saveFromResponse` 之前存下的 Jupyter `_xsrf`/session cookie。当前有 `seededCookie` 去重，正常路径不易触发，但换账号或同一 host 再次 seed 时可能丢会话。

**建议**：改为按 name 合并（保留服务端下发的 cookie），而非整体替换。

---

## 附：未能验证项

- 环境缺少 JDK 17 与 Android SDK 36，`./gradlew testDebugUnitTest` 无法执行，B-01~B-13 均未经编译/测试验证。
- `.github/workflows/build-apk.yml` 新增的「安装所需 SDK 组件」步骤在 `sdkmanager` 未找到时会直接失败（未加 `|| true`）；是否为 runner 实际风险需在 CI 上验证，暂未计入编号。
