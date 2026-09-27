# ComfyUI Mobile 错误报告

- 审查分支：`origin/dev`
- HEAD：`420d8ab`（`fix(v0.2.45): 修 MainActivity KDoc 里的 image/*`）
- 版本：v0.2.45
- 日期：2026-09-27
- 范围：最新 `dev` 源码审查，重点覆盖 v0.2.45 图片导入、多账号切换、JobMonitor、Comfy WebSocket

只收录已对照源码钉死的缺陷。风格问题、推测性隐患不列入。

---

## 摘要

| 编号 | 严重程度 | 模块 | 一句话 |
|------|----------|------|--------|
| B-01 | 高 | 图片导入 | 未连接时分享/导入工作流必失败，内容不落盘 |
| B-02 | 高 | 图片导入 | 没有 `singleTop`，已运行 App 再接收分享会新建未连接 Activity |
| B-03 | 中 | 图片导入 | 拿不到文件名时一律叫 `shared-image.png`，WebP 被当 PNG 解析 |
| B-04 | 高 | 图片导入 | 原生解析失败被吞掉，未连接最长卡 90 秒后报「前端桥接超时」 |
| B-05 | 高 | 图片导入 | 已导出 Activity 在主线程裸查 URI，无权限即崩 |
| B-06 | 高 | 多账号 | 切账号后 CookieJar / 终端仍是旧账号 |
| B-07 | 高 | 多账号 | DataStore 任意写入会把刚切好的账号打回去 |
| B-08 | 中 | 多账号 | 旧账号网络协程仍把积分/项目写进新面板 |
| B-09 | 中 | 后台任务 | JobMonitor 成功结束 / 用户停止时不删 `authCookies` |
| B-10 | 中 | WebSocket | Comfy `onOpen` 不认自己关的连接，断开后状态被打回「已连接」 |

B-01 ~ B-05 会叠在一起：相册分享进一个未连接的新 Activity，默认文件名把 WebP 判成 PNG，原生失败后卡 90 秒，最后报桥接超时。

B-06 ~ B-08 会叠在一起：Cookie 残留 + 磁盘快照回写 + 过期协程写 UI，表现为「切了还是旧身份 / 启不了 GPU / 面板数字是上一个人的」。

---

## B-01 未连接分享导入必失败，内容不落盘

**严重程度：** 高  
**模块：** 工作流导入  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/bridge/ComfyBridge.kt` 第 107 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 2068–2070、3612–3652 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/network/ComfyClient.kt` 第 232 行

**触发条件**

冷启动后直接从相册「分享 / 打开方式」导入 PNG/WebP；或 App 已在运行，系统又新建了一个未连接的 `MainActivity`（见 B-02）。此时 `activeServer == null`，`ComfyClient.baseUrl` 仍是空串。

**实际行为**

导入失败，工作流既不进列表也不落盘。用户看到的是连接/请求类错误，不是「未连接也可先保存在本机」。

**期望行为**

README 与 v0.2.45 注释写明：PNG / WebP 原生解析不依赖服务器，未连接也能导入。未连接时应走本地快照分支，并把正文写入本机。

**原因**

`serverWorkflowStoreAvailable` 默认是 `true`，只有 `connect()` 才会改掉。未连接时仍走云端分支：

```kotlin
@Volatile var serverWorkflowStoreAvailable: Boolean = true
```

```kotlin
val entry = if (bridge?.serverWorkflowStoreAvailable == true) {
    try {
        val existingPaths = client.listWorkflows().mapTo(mutableSetOf()) { it.path }
        // ...
        client.writeWorkflow("workflows/$candidateName", json.toString(), overwrite = false)
    } catch (error: IllegalStateException) {
        if (!isUserdataUnavailable(error)) throw error
        localOnlyEntry(candidateName, json)
    }
}
```

`listWorkflows()` 会请求 `"" + "/v2/userdata?..."`。OkHttp 对无 scheme 的 URL 抛 `IllegalArgumentException`，这里只 catch 了 `IllegalStateException`，异常直接冒到 `runOperation`。

就算以后把异常接住走进本地分支，落盘仍会被丢掉：

```kotlin
private suspend fun cacheWorkflowContent(serverUrl: String, path: String, json: String) {
    if (serverUrl.isBlank() || path.isBlank() || json.isBlank()) return
```

`activeServer?.baseUrl.orEmpty()` 是空串，内存缓存和磁盘快照都不会写。

**建议**

1. 未连接（`activeServer == null` 或 `baseUrl` 为空）时强制走 `localOnlyEntry`，不要调 `listWorkflows` / `writeWorkflow`。
2. `cacheWorkflowContent` 在无服务器地址时使用稳定的本地占位 key（例如 `"local"`），允许未连接导入落盘。
3. `serverWorkflowStoreAvailable` 的默认值与 `connect()` 复位保持一致（探测完成前按不支持云端存储处理）。

---

## B-02 没有 `singleTop`，分享会新建未连接 Activity

**严重程度：** 高  
**模块：** Intent / Activity 生命周期  
**文件：**

- 当前工作区 `/app/src/main/AndroidManifest.xml` 第 51–55 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainActivity.kt` 第 70–71、84–88 行

**触发条件**

App 已经连着服务器在前台或后台，用户从相册再「分享」一张带工作流的图。

**实际行为**

系统按默认 `standard` 再起一个 `MainActivity`。`by viewModels()` 是 Activity 作用域，新实例里 `client.baseUrl` 为空、`activeServer` 为空，然后踩中 B-01。原来那个已连接的界面还在下面。用户看到导入失败；返回后进到旧界面，这次分享等于没发生。

**期望行为**

复用当前 `MainActivity`，走 `onNewIntent`，在已连接的 ViewModel 里导入。

**原因**

`MainActivity` 未设 `launchMode`。`onNewIntent` 是为了复用当前实例写的，但相册的 `ACTION_SEND` 一般不带 `FLAG_ACTIVITY_SINGLE_TOP`，这个回调根本不会走。通知点击能进 `onNewIntent`，是因为 `JobNotificationNavigation` 自己加了 `SINGLE_TOP`。

**建议**

给 `MainActivity` 加 `android:launchMode="singleTop"`（或 `singleTask`），并在 `onNewIntent` 里 `setIntent` 后处理分享。

---

## B-03 默认文件名把 WebP 钉死成 PNG

**严重程度：** 中  
**模块：** 图片导入 MIME 分派  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainActivity.kt` 第 108–112 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 3565、3572–3575 行

**触发条件**

`ACTION_SEND` / `ACTION_VIEW` 的 `DISPLAY_NAME` 查不到（部分分享源、`file://`、query 返回空），实际文件是 WebP，`intent.type` 是 `image/webp`。

**实际行为**

原生解析走 PNG 签名校验，直接失败；再掉进 B-04 的前端回退。已连接时还能靠前端救回来，未连接就失败或卡死。

**期望行为**

扩展名缺失时以 MIME 为准；MIME 也缺失时用魔数（RIFF/WEBP 或 PNG 签名）判断。

**原因**

```kotlin
val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    ?: "shared-image.png"
```

```kotlin
val nativeKind = when {
    extension == "png" || mimeType.equals("image/png", ignoreCase = true) -> "png"
    extension == "webp" || mimeType.equals("image/webp", ignoreCase = true) -> "webp"
    else -> null
}
```

`when` 先看扩展名。默认名把扩展名钉死成 `png`，后面的 `image/webp` 永远轮不到。这里也没有用 `uri.lastPathSegment` 做回退。

**建议**

1. 默认名按 MIME 生成：`image/webp` → `shared-image.webp`，`image/png` → `shared-image.png`。
2. `nativeKind` 优先 MIME，其次扩展名，最后读文件头。
3. query 包在 `runCatching` 里，失败时不要让主线程崩（见 B-05）。

---

## B-04 原生解析失败被吞掉，未连接最长卡 90 秒

**严重程度：** 高  
**模块：** 图片导入回退路径  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 3577–3588 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/bridge/ComfyBridge.kt` 第 333–349、667–668 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainActivity.kt` 第 60–71 行

**触发条件**

图片里没有 workflow、文件损坏，或 B-03 把 WebP 当 PNG 读；同时还没连上 ComfyUI（分享入口的常态）。

**实际行为**

用户要干等最多 90 秒，最后看到的是「前端桥接超时」，真正的原因（没有工作流 / 不是 PNG）被丢掉。注释写的是「仅 WebP 回退前端」，代码对 PNG 同样回退。AVIF 分享没有原生路径，未连接时同样会卡满 90 秒。

**期望行为**

未连接且原生解析失败时，立刻报「图片中没有可导入的 ComfyUI 工作流」，不要去等隐藏 WebView。

**原因**

```kotlin
val native = nativeKind?.let { kind ->
    withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { WorkflowImageReader.readWorkflow(it, kind) }
                ?: error("无法读取所选图片")
        }.getOrNull()
    }
}
when {
    native != null -> native
    nativeKind != null && bridge == null -> error("无法读取所选图片里的工作流")
    else -> (bridge ?: error("前端桥接不可用")).extractWorkflowFromImage(uri, mimeType, filename)
}
```

`onCreate` 里 `attachBridge` 在 `handleSharedImage` 之前，所以 `bridge == null` 几乎走不到。`extractWorkflowFromImage` 一上来就是 `awaitReady(timeoutMillis = 90_000L)`。隐藏 WebView 这时还没加载 ComfyUI 页面，只能轮询到超时。

**建议**

1. 未连接（`activeServer == null` 或 `bridgeReady == false`）时，原生失败直接抛解析错误，不要回退前端。
2. 回退前端前保留原生异常信息，超时文案带上「图片里可能没有工作流」。
3. AVIF 在未连接时给出明确提示：需要先连接服务器。

---

## B-05 已导出 Activity 在主线程裸查 URI，无权限即崩

**严重程度：** 高  
**模块：** 分享入口稳定性  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainActivity.kt` 第 98–112 行
- 当前工作区 `/app/src/main/AndroidManifest.xml` 第 51–83 行

**触发条件**

任意 App 对 `MainActivity` 发 `ACTION_SEND` / `ACTION_VIEW`，`EXTRA_STREAM` / `data` 是本 App 读不了的 `content://`（没 grant）、无效 URI，或 provider 在 query 时抛 `SecurityException` / `IllegalArgumentException`。

**实际行为**

异常发生在 `onCreate` / `onNewIntent` 主线程，没有 try/catch，Activity 直接 crash。这是 v0.2.45 新的导出入口，不再只走应用内的 `OpenDocument`。

**期望行为**

无权限或无效 URI 时给出 Toast / 界面错误，Activity 保持可用。

**原因**

```kotlin
val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    ?: "shared-image.png"
viewModel.importSharedImage(uri, name, mimeType)
```

`query` 和后面的 `openInputStream`（在协程里）都可能对无权限 URI 抛 `SecurityException`。前者在主线程，必崩。

**建议**

`handleSharedImage` 整体包 `runCatching`；query / `getType` 失败时用默认名并继续，权限错误则提示「无法读取这张图片」。

---

## B-06 切账号后 CookieJar / 终端仍是旧账号

**严重程度：** 高  
**模块：** AI Studio 多账号  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 585–594、1113–1114 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/network/AiStudioKernelClient.kt` 第 71–89、406–434 行

**触发条件**

账号 A 连过终端（jar 里已有 `ide-proxy` / `user-{uid}-{pid}`）→ 切到账号 B → 再启动 GPU 或连 ComfyUI。同一账号停 GPU 再启动也会踩同一条短路。

**实际行为**

B 的请求混着 A 的项目 Cookie。轻则 403 / 登录墙，重则打到 A 的实例。终端 WS 也还连在 A 的机器上，界面已经是 B。

**期望行为**

切账号时断开终端、清掉项目级 Cookie，下次启动按新账号重新握手。停 GPU 再启动时，强制换新的 `ide-proxy`。

**原因**

`selectAiStudioAccount` 只清了面板数据，终端、`kernelEndpoint`、CookieJar 都还在。`seedCookies` 按 name 合并，不会丢掉旧的 `ide-proxy`。`ensureAiStudioProjectCookies` 看到 jar 里已有项目 Cookie 就直接返回：

```kotlin
fun selectAiStudioAccount(accountId: String) {
    // ...
    persistAiStudio(accounts, accountId)
    _state.update { it.copy(aiStudio = clearedAccountPanel(panel, accounts, accountId)) }
}
```

```kotlin
private suspend fun ensureAiStudioProjectCookies(servingUrl: String): Boolean {
    if (kernelClient.hasProjectCookies()) return true
```

注释已经写明：实例重建后旧 `ide-proxy` 会失效。`hasProjectCookies()` 仍为 true，连接前不会去换新 Cookie。

**建议**

1. `selectAiStudioAccount` 调用 `aiStudioDisconnectConsole()`，并清空 CookieJar 中的项目级 Cookie（至少 `ide-proxy` 与 `user-*`）。
2. `hasProjectCookies()` 增加「是否属于当前 uid/pid」判断，不能只看名字在不在。
3. 停止再启动 GPU 时强制 `hasProjectCookies() == false`，重新 `warmUpProjectCookies`。

---

## B-07 DataStore 任意写入会把刚切好的账号打回去

**严重程度：** 高  
**模块：** 账号持久化 / 状态同步  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 230–280、585–594、1626–1630 行

**触发条件**

切账号或刚登录成功之后，任意 DataStore 写入先完成（`saveServer`、Cookie 防抖落盘、`setLastUpdateCheck`、自动签到开关等）。

**实际行为**

`activeAccountId` 和账号列表被旧快照盖掉。界面跳回上一个账号；随后的启动 GPU、签到、拉项目都打到旧账号上。这和 v0.2.37「切换账号后无法启动 GPU」是同一类残留。

**期望行为**

内存里刚切好的账号不被其它 DataStore 字段的发射覆盖；只在账号相关 key 真正变化时同步。

**原因**

切账号先改内存，`persistAiStudio` 另起协程写盘。`settings.collect` 每次发射都无条件用磁盘上的 `aiStudioActiveId` 覆盖内存，不管这次发射是不是账号写入引起的：

```kotlin
aiStudio = _state.value.aiStudio.copy(
    accounts = stored.aiStudioAccounts,
    activeAccountId = stored.aiStudioActiveId.ifBlank { null },
    consoleQuickCommands = stored.consoleQuickCommands,
    consoleThemeId = stored.consoleThemeId,
)
```

地址输入框已经做了「只在首次 / 未连接时回填」保护，账号字段没有同等保护。

**建议**

1. 账号字段采用与 `serverInput` 相同的策略：内存已有更新且尚未落盘完成时，不要用旧快照覆盖。
2. 或把 `persistAiStudio` 改成与状态更新同步的挂起写入，切账号完成后再允许其它 collect 覆盖。
3. collect 里只在 `stored.aiStudioActiveId` / accounts 与当前内存不同、且不是「内存更新尚未落盘」时才写回。

---

## B-08 切账号后，旧账号的网络协程仍写新面板

**严重程度：** 中  
**模块：** AI Studio 面板刷新  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 751–790、1327–1346 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/ui/ComfyMobileApp.kt` 第 4352–4358 行

**触发条件**

账号页 `LaunchedEffect(activeAccountId)` 会立刻 `aiStudioRefreshAccount` / `aiStudioLoadProjects`。前一个账号的请求还在飞，切走后返回。

**实际行为**

新账号面板出现旧账号的积分、算力卡、项目列表。`aiStudioLoadProjects` 开头还有 `if (aiStudioProjectJob?.isActive == true) return`，旧请求占着 job 时新账号这次拉取直接被丢掉。

**期望行为**

切账号时取消旧刷新；返回时若 `activeAccountId` 已变，丢弃结果。

**原因**

这些函数在启动时捕获 `account`，回来写 `_state` 时不再核对 `activeAccountId`。`selectAiStudioAccount` 也不取消 `aiStudioActionJob` / `aiStudioProjectJob` / `aiStudioStartPollJob`。

```kotlin
fun aiStudioRefreshAccount() {
    val account = _state.value.aiStudio.activeAccount() ?: return
    viewModelScope.launch {
        // ... 网络请求 ...
        _state.update {
            it.copy(aiStudio = it.aiStudio.copy(points = points, /* ... */))
        }
    }
}
```

**建议**

1. 写回前检查 `_state.value.aiStudio.activeAccountId == account.id`。
2. `selectAiStudioAccount` 取消 `aiStudioActionJob` / `aiStudioProjectJob` / `aiStudioStartPollJob`。
3. `aiStudioLoadProjects` 不要因为旧 job 还在就直接 return；应取消旧 job 再拉新账号。

---

## B-09 JobMonitor 成功结束 / 用户停止时不删 `authCookies`

**严重程度：** 中  
**模块：** 后台任务凭据生命周期  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/service/JobMonitorService.kt` 第 118、130–135、169–176、257、368–373、401 行

**触发条件**

任务正常完成，或通知栏 / 界面发出 `ACTION_STOP`。启动失败走进 `onStartCommand` 的 catch 同样触发。

**实际行为**

`authCookies` 里是完整的反代登录 Cookie（含 BDUSS / `ide-proxy`）。只有「任务从服务器消失」和「连续轮询失败」两条路径会 `remove`。前台服务常驻时，每成功跑完一个任务就多留一份凭据。

**期望行为**

任务无论成功、停止、启动失败，都按 `promptId` 清掉对应 Cookie。

**原因**

v0.1.87 把 Cookie 改成按 `promptId` 分表之后，成功收尾和 STOP 仍只清了 `monitors` / `workflowNames` / `serverUrls`：

```kotlin
monitors.remove(promptId)
workflowNames.remove(promptId)
workflowPaths.remove(promptId)
serverUrls.remove(promptId)
progressUpdatedAt.remove(promptId)
staleNotified.remove(promptId)
// 缺少 authCookies.remove(promptId)
```

启动失败的 catch（130–135 行）同样没清。

**建议**

抽出 `forgetJob(promptId)`，所有收尾路径统一清掉 `authCookies`。

---

## B-10 Comfy WebSocket 的 `onOpen` 不认「自己关的连接」

**严重程度：** 中  
**模块：** Comfy 连接状态  
**文件：**

- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/network/ComfyClient.kt` 第 550–593 行
- 当前工作区 `/app/src/main/java/com/local/comfyuimobile/MainViewModel.kt` 第 1929–1947、4341–4353 行

**触发条件**

正在握手或刚连上时点断开 / 换服务器。`closeWebSocket()` 已经把 socket 置空，OkHttp 仍可能先回调 `onOpen`。

**实际行为**

`disconnect()` 已经把 `activeServer` 清掉、状态设成 `DISCONNECTED`，随后 `onOpen` 再写成 `CONNECTED`。顶栏显示已连接，实际没有服务器、也没有可用 socket。

**期望行为**

主动关闭后迟到的 `onOpen` 被忽略，状态保持已断开。

**原因**

`closedByUs` 只在 `onFailure` / `onClosed` 里看，`onOpen` 无条件调用上层。终端侧专门做了 `terminalSocket !== webSocket` 判断，Comfy 这条路径没有。

```kotlin
override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
```

```kotlin
onOpen = {
    reconnectJob?.cancel()
    wsReconnectBackoffMs = WS_RECONNECT_MIN_MS
    _state.update { it.copy(status = ConnectionStatus.CONNECTED, connectionMessage = "已连接 ${it.activeServer?.name.orEmpty()}") }
}
```

**建议**

`onOpen` 里判断 `socket !== webSocket` 或 `closedByUs.contains(webSocket)`，不是当前连接就 return。上层也可在 `activeServer == null` 时忽略 `onOpen`。

---

## 叠加路径（建议优先修）

### 分享导入（B-01 + B-02 + B-03 + B-04 + B-05）

典型复现：

1. App 已连接，从相册分享一张带工作流的 WebP。
2. 系统新建未连接 `MainActivity`（B-02）。
3. 文件名退化成 `shared-image.png`，WebP 当 PNG 读（B-03）。
4. 原生失败被吞，隐藏 WebView `awaitReady` 最长 90 秒（B-04）。
5. 即便原生成功，空 `baseUrl` 让云端写入抛错，本地也不落盘（B-01）。
6. 若分享源没 grant URI，主线程直接崩（B-05）。

建议修复顺序：B-05（崩）→ B-02（复用 Activity）→ B-01（未连接落盘）→ B-03 / B-04（MIME 与回退）。

### 切账号（B-06 + B-07 + B-08）

典型复现：

1. 账号 A 已连终端。
2. 切到账号 B：面板清空，但 CookieJar / 终端仍是 A（B-06）。
3. 期间任意 DataStore 写入把 active 账号打回 A（B-07）。
4. A 的刷新协程返回，积分/项目写到当前面板（B-08）。

建议修复顺序：B-07（状态回写）→ B-06（身份残留）→ B-08（过期协程）。

---

## 审查时排除的项

以下内容看过，当前不作为缺陷收录：

- `WorkflowImageReader` 的 WebP EXIF 主路径（`workflow:{JSON}`、`Exif\0\0` 前缀）与官方 `getWebpMetadata` 对齐，单测覆盖这条；真正打挂的是 Intent / MIME 分派，以及「未连接也要能保存」没有闭环。
- v0.2.44 LoRA 选项对话框已从 `AlertDialog` 换成自控宽高的 `Dialog` + `LazyColumn.weight(1f)`，列表显示错乱按该提交已修，本次未再发现同源逻辑错误。
- `ComboPickerDialog` 对所有 combo 都调用 `shortLoraName`，会去掉采样器等选项里碰巧出现的 `.pt` 后缀；这是展示层取舍，未单独开缺陷。

---

## 建议验证

1. 冷启动、未连接：从相册分享 PNG / WebP / AVIF，确认本地列表出现条目、杀进程后仍在。
2. 已连接：再分享一张图，确认仍在同一 Activity、导入进当前服务器工作流列表。
3. 无 `DISPLAY_NAME` 的 WebP 分享：确认走 WebP 解析，而不是 PNG 签名失败。
4. 无权限 URI：确认 Toast / 错误提示，Activity 不崩。
5. 账号 A 连终端后切到 B，立刻启动 GPU / 连 ComfyUI：确认请求带 B 的 Cookie，终端已断开。
6. 切账号后立刻改服务器地址或触发更新检查：确认当前账号不被打回去。
7. 切账号时旧刷新未完成：确认新面板不会出现旧积分/项目。
8. 提交任务至完成、以及通知栏停止：确认对应 `promptId` 的 `authCookies` 已移除（可用诊断日志只打名字、不打值）。
9. 连接握手中点断开：确认顶栏保持「已断开」，不会闪回「已连接」。
