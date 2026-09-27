# ComfyUI Mobile v0.2.46 缺陷修复报告

- 审查输入：`bug-report-v0.2.45.md`（B-01 ~ B-10）
- 审查基线：`420d8ab`
- 修复提交：`9eb72f1`（分支 `dev`）
- 版本：v0.2.46（versionCode 246）
- CI：编译通过 → 已发布
  - debug: `https://github.com/zyzyj/ComfyUIMobile/releases/download/v0.2.46/ComfyUIMobile-v0.2.46-debug.apk`

---

## 一、核实结论

**10 条缺陷全部属实。** 逐条对照源码确认了报告引用的行号、变量与调用链，没有一条是误报。

报告里引用的关键事实我都直接读源码验证过：

| 报告断言 | 核实结果 |
|---|---|
| `serverWorkflowStoreAvailable` 默认 `true` | 属实（ComfyBridge.kt:107） |
| 导入只 catch `IllegalStateException` | 属实（MainViewModel.kt:3630），空 URL 抛 `IllegalArgumentException` 会漏出 |
| `cacheWorkflowContent` 空 `serverUrl` 直接 return | 属实（MainViewModel.kt:2069），未连接导入不落盘 |
| `MainActivity` 未设 `launchMode` | 属实（AndroidManifest.xml:51） |
| 兜底名写死 `shared-image.png` | 属实（MainActivity.kt:111） |
| `nativeKind` 扩展名优先于 MIME | 属实（MainViewModel.kt:3572） |
| `extractWorkflowFromImage` 一进来就 `awaitReady(90_000)` | 属实（ComfyBridge.kt:668） |
| `selectAiStudioAccount` 不动终端 / CookieJar | 属实（MainViewModel.kt:585） |
| `settings.collect` 无条件覆盖 `activeAccountId` | 属实（MainViewModel.kt:276） |
| 成功/停止/启动失败三条路径不删 `authCookies` | 属实（JobMonitorService.kt:130/170/368） |
| Comfy `onOpen` 无条件通知上层 | 属实（ComfyClient.kt:560） |

**额外确认一点**：报告说 B-04 里 `bridge == null` 几乎走不到——核实为真。`onCreate` 中 `attachBridge` 在 `handleSharedImage` 之前执行，所以 `bridge` 恒非空，那个分支确实是死代码。这也是为什么原来会一路走到 `awaitReady`。

---

## 二、逐条修复

### B-01 未连接导入必失败，内容不落盘（高）

**根因（两处叠加）**

1. 分支判断只看 `bridge?.serverWorkflowStoreAvailable`（默认 `true`），不检查是否真的连着服务器。未连接时 `baseUrl` 是空串，`listWorkflows()` 拼出 `"/v2/userdata?path=workflows"`，OkHttp 对无 scheme 的 URL 抛 `IllegalArgumentException`——而这里只 catch `IllegalStateException`，异常直接冒到 `runOperation`。
2. 就算走进本地分支，`cacheWorkflowContent(activeServer?.baseUrl.orEmpty(), ...)` 传的是空串，被函数开头的 `serverUrl.isBlank()` 判断直接跳过，内存和磁盘都不写。用户导入完杀掉进程就什么都没了。

**修复**

- [MainViewModel.kt:3634](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:3634)：分支条件加上 `connected = status == CONNECTED && baseUrl.isNotBlank()`
- [MainViewModel.kt:3648](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:3648)：补 catch `IllegalArgumentException`，同样降级本地
- [WorkflowSnapshotStore.kt:189](app/src/main/java/com/local/comfyuimobile/data/WorkflowSnapshotStore.kt:189)：新增 `LOCAL_SCOPE = "@local"`
- [MainViewModel.kt:2147](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:2147)：新增 `snapshotScope()` / `snapshotScopeFor()`，空地址一律用 `@local`
- [MainViewModel.kt:2098](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:2098)：`readWorkflowWithFallback` 走同一个 scope；地址为空时不再发注定失败的请求
- `refreshWorkflowsInternal` 两处 `workflowSnapshots.list(...)` 同步改用 scope，未连接导入的工作流能在列表里显示

### B-02 没有 singleTop，分享会新建未连接 Activity（高）

[AndroidManifest.xml:54](app/src/main/AndroidManifest.xml:54) 加 `android:launchMode="singleTop"`。相册的 `ACTION_SEND` 一般不带 `FLAG_ACTIVITY_SINGLE_TOP`，原来会按 `standard` 新起一个 Activity，新实例里 `activeServer` 为空，直接踩 B-01。

### B-03 默认文件名把 WebP 钉死成 PNG（中）

- [MainActivity.kt:130](app/src/main/java/com/local/comfyuimobile/MainActivity.kt:130)：新增 `defaultSharedImageName()`，按 MIME 给扩展名（`image/webp` → `shared-image.webp`），MIME 没给具体格式时退到 `uri.lastPathSegment`
- [MainViewModel.kt:3655](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:3655)：判格式优先级改为 **MIME > 扩展名 > 文件头**
- [WorkflowImageReader.kt:34](app/src/main/java/com/local/comfyuimobile/bridge/WorkflowImageReader.kt:34)：新增 `detectKind()`，读文件头认 PNG / WebP，认不出返回 `null`

### B-04 原生解析失败被吞，未连接卡 90 秒（高）

- `native` 改为保留 `Result`（原来是 `getOrNull()`，把真实原因丢了）
- 新增 `frontendUsable = bridge != null && status == CONNECTED`：未连接时直接把原生解析的错误信息抛给用户（"图片中没有可导入的 ComfyUI 工作流" / "所选文件不是有效的 WebP 图片"）
- AVIF 未连接时给明确提示："未连接服务器时无法解析这种图片（AVIF 需要先连接服务器）"
- 已连接时行为不变，仍回退前端

### B-05 已导出 Activity 在主线程裸查 URI（高）

[MainActivity.kt:106](app/src/main/java/com/local/comfyuimobile/MainActivity.kt:106)：`contentResolver.getType()` 与 `query()` 全部包 `runCatching`，失败记日志并继续（用兜底名），不再让 Activity 崩。

### B-06 切账号后 CookieJar / 终端仍是旧账号（高）

- [MainViewModel.kt:619](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:619)：`selectAiStudioAccount` 里调 `aiStudioDisconnectConsole()` + `kernelClient.clearProjectCookies()`
- [AiStudioKernelClient.kt:445](app/src/main/java/com/local/comfyuimobile/network/AiStudioKernelClient.kt:445)：新增 `clearProjectCookies()`，只删 `ide-proxy` 与 `user-*`，账号级 Cookie（BDUSS 等）保留
- [MainViewModel.kt:1662](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:1662)：停止 GPU 成功后也清——实例重建后旧 `ide-proxy` 已失效，但名字还在，`hasProjectCookies()` 仍返回 true，下次启动会跳过预热

### B-07 DataStore 任意写入把刚切好的账号打回去（高）

- [MainViewModel.kt:235](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:235)：新增 `pendingAccountId`
- 切账号时置位，`persistAiStudio` 写盘成功后清除
- [MainViewModel.kt:289](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:289)：`settings.collect` 里 `activeAccountId` 改为 `pendingAccountId ?: stored.aiStudioActiveId...`

这与 `serverInput` 已有的"只在首次 / 未连接时回填"是同一类保护，账号字段之前漏了。

### B-08 旧账号网络协程仍写新面板（中）

- 新增 `isActiveAiStudioAccount(accountId)`（核对 `activeAccountId` 且账号仍在列表里）
- `aiStudioRefreshAccount` / `aiStudioLoadProjects` 写回前各核对一次
- `selectAiStudioAccount` 取消 `aiStudioActionJob` / `aiStudioProjectJob` / `aiStudioStartPollJob`
- `aiStudioLoadProjects` 不再因旧 job 在跑就 return：旧 job 属于上一个账号，取消它再拉新的（面板已有数据且非 silent 时才保留原有短路行为）

### B-09 成功结束 / 停止时不删 authCookies（中）

[JobMonitorService.kt:62](app/src/main/java/com/local/comfyuimobile/service/JobMonitorService.kt:62)：抽出 `forgetJob(promptId)`，统一清 `monitors` / `workflowNames` / `workflowPaths` / `serverUrls` / `progressUpdatedAt` / `staleNotified` / `authCookies`。6 条收尾路径全部改用（原来只有 2 条清 Cookie）。

`startMonitor` 里那个 `monitors.remove(promptId)?.cancel()`（第 229 行）保持原样——那是同一 promptId 重提交时顶替旧协程，此时 Cookie 刚由新 intent 写入，不能清。

### B-10 Comfy onOpen 不认自己关的连接（中）

- [ComfyClient.kt:560](app/src/main/java/com/local/comfyuimobile/network/ComfyClient.kt:560)：`onOpen` 加 `if (webSocket !== socket || closedByUs.contains(webSocket)) return`
- [MainViewModel.kt:4456](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt:4456)：onOpen 逻辑抽成 `onComfySocketOpen()`，前面再加一道 `activeServer == null` 判断

终端侧早就有 `terminalSocket !== webSocket` 的判断，Comfy 这条路径补上同样的。

---

## 三、验证

| 项目 | 结果 |
|---|---|
| CI 编译（含单测） | 通过（run 36337719643） |
| 发布 | v0.2.46 已发布 |
| 新增单测 | `detectsKindFromHeader`（PNG / WebP / 无法识别 / null 四种） |

**未能验证的部分**：这 10 条全部涉及真机交互（分享 Intent、多账号切换、任务生命周期、WebSocket 时序），容器内无 Android 环境，**没有做真机验证**。修复是静态推演 + 编译验证，请按下面清单实测。

---

## 四、建议实测清单

1. **冷启动、未连接**：从相册分享 PNG / WebP → 本地列表出现条目，杀进程后仍在（B-01）
2. **已连接**：再分享一张图 → 仍在同一 Activity，导入进当前服务器工作流列表（B-02）
3. **无 DISPLAY_NAME 的 WebP 分享** → 走 WebP 解析成功，不是 PNG 签名失败（B-03）
4. **无权限 URI**：Toast / 错误提示，Activity 不崩（B-05）
5. **未连接分享一张没有工作流的图** → 几秒内报"图片中没有可导入的 ComfyUI 工作流"，不卡 90 秒（B-04）
6. **账号 A 连终端后切到 B**，立刻启动 GPU / 连 ComfyUI → 请求带 B 的 Cookie，终端已断开（B-06）
7. **切账号后立刻改服务器地址 / 触发更新检查** → 当前账号不被打回（B-07）
8. **切账号时旧刷新未完成** → 新面板不会出现旧积分 / 项目（B-08）
9. **提交任务至完成、通知栏停止** → 对应 `promptId` 的 `authCookies` 已移除（B-09）
10. **连接握手中点断开** → 顶栏保持"已断开"，不闪回"已连接"（B-10）

---

## 五、审查报告中未采纳的部分

报告"审查时排除的项"里列的 3 条，我核实后同意不作为缺陷处理：

- `WorkflowImageReader` 的 WebP EXIF 主路径与官方 `getWebpMetadata` 对齐 — 同意，真正的问题在 Intent / MIME 分派（B-03 / B-04 已修）
- v0.2.44 LoRA 对话框改动未见同源逻辑错误 — 同意，本次未再发现
- `ComboPickerDialog` 对所有 combo 调 `shortLoraName` 会去掉采样器里碰巧出现的 `.pt` 后缀 — 同意这是展示层取舍；但如果之后有用户反馈"采样器名字被截短"，这里就是根因，值得单开一条

---

## 六、改动文件

| 文件 | 改动 |
|---|---|
| [AndroidManifest.xml:54](app/src/main/AndroidManifest.xml:54) | B-02 `singleTop` |
| [MainActivity.kt:106](app/src/main/java/com/local/comfyuimobile/MainActivity.kt:106) | B-03 兜底名、B-05 `runCatching` |
| [MainViewModel.kt](app/src/main/java/com/local/comfyuimobile/MainViewModel.kt) | B-01 / B-03 / B-04 / B-06 / B-07 / B-08 / B-10 |
| [WorkflowImageReader.kt:34](app/src/main/java/com/local/comfyuimobile/bridge/WorkflowImageReader.kt:34) | B-03 `detectKind()` |
| [WorkflowSnapshotStore.kt:189](app/src/main/java/com/local/comfyuimobile/data/WorkflowSnapshotStore.kt:189) | B-01 `LOCAL_SCOPE` |
| [AiStudioKernelClient.kt:445](app/src/main/java/com/local/comfyuimobile/network/AiStudioKernelClient.kt:445) | B-06 `clearProjectCookies()` |
| [ComfyClient.kt:560](app/src/main/java/com/local/comfyuimobile/network/ComfyClient.kt:560) | B-10 `onOpen` 身份判断 |
| [JobMonitorService.kt:62](app/src/main/java/com/local/comfyuimobile/service/JobMonitorService.kt:62) | B-09 `forgetJob()` |
| [WorkflowImageReaderTest.kt](app/src/test/java/com/local/comfyuimobile/bridge/WorkflowImageReaderTest.kt) | 新增 `detectsKindFromHeader` |
| [RELEASE_NOTES.md:1](RELEASE_NOTES.md:1) | v0.2.46 条目 |
