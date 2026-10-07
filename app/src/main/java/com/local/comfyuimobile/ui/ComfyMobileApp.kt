package com.local.comfyuimobile.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.runtime.derivedStateOf
import com.local.comfyuimobile.mcp.McpCallLog
import com.local.comfyuimobile.mcp.McpServerManager
import com.local.comfyuimobile.mcp.McpToolRegistry
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.local.comfyuimobile.MainViewModel
import com.local.comfyuimobile.network.AiStudioProtocol
import com.local.comfyuimobile.network.LanAddress
import com.local.comfyuimobile.AdvancedEditorActivity
import com.local.comfyuimobile.AiStudioLoginActivity
import com.local.comfyuimobile.bridge.ComfyBridge
import com.local.comfyuimobile.bridge.FieldValidator
import com.local.comfyuimobile.data.CachePolicy
import com.local.comfyuimobile.data.RecentWorkflows
import com.local.comfyuimobile.data.UrlQuery
import com.local.comfyuimobile.model.LoraStrengthMatrix
import com.local.comfyuimobile.model.LoraStrengthRun
import com.local.comfyuimobile.model.LoraStrengthSlot
import com.local.comfyuimobile.model.LoraStrengthTarget
import com.local.comfyuimobile.model.StrengthPhase
import com.local.comfyuimobile.data.WorkflowBrowser
import com.local.comfyuimobile.data.WorkflowPath
import com.local.comfyuimobile.model.AppDestination
import com.local.comfyuimobile.model.AppUiState
import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.StorageBucket
import com.local.comfyuimobile.model.StorageCleanTarget
import com.local.comfyuimobile.model.StorageStats
import com.local.comfyuimobile.model.AiStudioProject
import com.local.comfyuimobile.model.ComfyAvailability
import com.local.comfyuimobile.model.AiStudioState
import com.local.comfyuimobile.model.BatchCompareLogic
import com.local.comfyuimobile.model.BatchPhase
import com.local.comfyuimobile.model.BatchRun
import com.local.comfyuimobile.model.ConnectionStatus
import com.local.comfyuimobile.model.JobProgressText
import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.JobSummary
import com.local.comfyuimobile.model.MediaKind
import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import com.local.comfyuimobile.model.ParameterSection
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.model.ResultSource
import com.local.comfyuimobile.model.SeedMode
import com.local.comfyuimobile.model.ServerProfile
import com.local.comfyuimobile.model.WorkflowEntry
import com.local.comfyuimobile.model.WorkflowConnectionMarker
import com.local.comfyuimobile.model.WorkflowNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults

private const val IME_RELOCATION_SUPPRESSION_MILLIS = 700L

/**
 * v0.1.90：页面。
 *
 * 「账号」放第一位——这个 App 现在既能直连 ComfyUI，也能从 AI Studio 拉起
 * 云端算力，两种用法都不该被「先填一个服务器地址」挡在门外。
 *
 * PARAMETERS 仍是有效页面（从工作流列表点进去），但**不进底栏**：
 * 它不是一个常驻入口，占着底栏一格只会让常用页更挤。底栏只渲染
 * [MainPage.bottomBarEntries]。
 */
private enum class MainPage(val label: String, val icon: ImageVector, val inBottomBar: Boolean = true) {
    // v0.2.54：底栏收成 5 个（Material 3 规范上限）：「更多」接管了控制台 / 任务 /
    // AI 助手。以前 6 个已经超出规范，标签被挤得很窄。
    ACCOUNT("账号", Icons.Outlined.AccountCircle),
    WORKFLOWS("工作流", Icons.Outlined.Folder),
    QUICK("快捷", Icons.Outlined.PlayArrow),
    RESULTS("结果", Icons.Outlined.Image),
    MORE("更多", Icons.Outlined.MoreHoriz),
    // 以下不进底栏，从「更多」或页面内进入。
    CONSOLE("控制台", Icons.Outlined.Computer, inBottomBar = false),
    TASKS("任务", Icons.AutoMirrored.Outlined.List, inBottomBar = false),
    PARAMETERS("参数", Icons.Outlined.Tune, inBottomBar = false),
    // v0.2.36：空间管理页。从设置进入。
    STORAGE("空间管理", Icons.Outlined.Memory, inBottomBar = false),
    // v0.2.88：MCP 服务页。从设置进入。
    MCP("MCP 服务", Icons.Outlined.Extension, inBottomBar = false),
    ;

    companion object {
        val bottomBarEntries: List<MainPage> = entries.filter { it.inBottomBar }
    }
}

private enum class ResultLayout { ALL, ALBUMS }
private data class ResultAlbum(val jobId: String, val media: List<ResultMedia>)

@Composable
fun ComfyMobileApp(viewModel: MainViewModel, bridge: ComfyBridge) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val advancedEditorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.finishAdvancedEditor(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (!message.isNullOrBlank()) {
            // v0.1.85：登录失效时给一句能照着做的指引。以前只报"连接失败"，
            // 用户压根不知道下一步该怎么办（其实是 Cookie 过期了，得重新获取）。
            val shown = if (state.cookieExpired) {
                "$message\n请在「设置」里重新获取并粘贴 Cookie，然后直接点连接。"
            } else {
                message
            }
            snackbar.showSnackbar(shown)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.advancedEditor) {
        if (state.advancedEditor) {
            advancedEditorLauncher.launch(
                Intent(context, AdvancedEditorActivity::class.java)
                    .putExtra(
                        AdvancedEditorActivity.EXTRA_SERVER_URL,
                        state.activeServer?.baseUrl.orEmpty(),
                    )
                    .putExtra(
                        AdvancedEditorActivity.EXTRA_WORKFLOW_PATH,
                        state.selectedWorkflow?.entry?.path.orEmpty(),
                    ),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        // v0.1.90：不再以「有没有连上服务器」决定进哪个页面。以前未连接就只能停在
        // 连接页，账号与控制台这类功能全被挡住。现在有服务器地址就走完整界面，
        // 连接表单收进账号页的可展开卡片里。
        //
        // 液态玻璃改造：极光渐变底铺在最下，上面所有玻璃面板透出它才有"玻璃感"。
        AuroraBackground {
            ConnectedApp(state, viewModel, snackbar)
        }
        // v0.2.33：全屏图片查看器同样挂在最外层，以主窗口浮层渲染。
        state.galleryViewer?.let { request ->
            ImageGalleryViewer(
                items = request.items,
                initialIndex = request.initialIndex,
                fromResults = request.fromResults,
                onDismiss = viewModel::dismissGalleryViewer,
                onSave = viewModel::saveResultWithFeedback,
                onShare = viewModel::shareResult,
                onOpen = viewModel::openResult,
                favoriteKeys = state.favoriteResultKeys,
                onFavorite = viewModel::toggleResultFavorite,
                onDelete = { item ->
                    viewModel.removeFromGalleryViewer(item)
                    viewModel.deleteLocalResults(listOf(item))
                },
            )
        }
        key(bridge.webView) {
            AndroidView(
                factory = { bridge.webView },
                modifier = Modifier.size(1.dp).alpha(0f),
            )
        }
        if (state.loading && state.advancedEditor) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("正在准备高级编辑…")
                }
            }
        }
    }
}


/**
 * v0.2.54：「更多」页。底栏受 Material 3 限制（最多 5 个）放不下的入口收在这里。
 *
 * v0.2.55：**不再放设置入口**——顶栏已经有设置图标，两处重复了。
 */
@Composable
private fun MoreScreen(onOpen: (MainPage) -> Unit) {
    val entries = listOf(
        Triple(MainPage.CONSOLE, "云端终端：执行命令、启动 ComfyUI", "控制台"),
        Triple(MainPage.TASKS, "服务器任务队列与历史", "任务"),
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("更多", style = MaterialTheme.typography.titleLarge)
        entries.forEach { (page, description, label) ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(page) },
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(page.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun ConnectedApp(state: AppUiState, viewModel: MainViewModel, snackbar: SnackbarHostState) {
    // v0.2.53：默认首页改为「账号」。
    // 账号页是「我是谁 → 我有什么 → 我能做什么」的入口：登录、启 GPU、连 ComfyUI
    // 都从这里开始；未连接时直接看到工作流页会是空的（没服务器就没有列表）。
    var page by rememberSaveable { mutableStateOf(MainPage.ACCOUNT) }
    /**
     * 返回栈（v0.2.55）。
     *
     * 以前 page 是单变量，切到子页后按系统返回键直接退 App——因为没有任何地方
     * 拦返回键，“返回”落到 Activity 默认行为（finish）。用户从「更多」进子页
     * 再返回，就直接退出软件了。现在记录来路，返回键逐级回退。
     */
    val pageStack = remember { mutableStateListOf<MainPage>() }
    /** 设置是独立页（v0.2.55）：以前它是顶栏图标弹出的对话框，与「更多」里的
     *  入口重复；现在只由顶栏进入，弹层改为整页，UI 不再受对话框尺寸限制。 */
    var settings by remember { mutableStateOf(false) }
    var resultSource by rememberSaveable { mutableStateOf(ResultSource.LOCAL) }
    var resultLayout by rememberSaveable { mutableStateOf(ResultLayout.ALBUMS) }
    var resultAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    // 切页统一走这里：底栏切主页面清空栈，子页入栈以便返回。
    fun navigateTo(target: MainPage) {
        if (target == page) return
        pageStack.clear()
        page = target
    }
    fun pushPage(target: MainPage) {
        if (target == page) return
        pageStack.add(page)
        page = target
    }
    fun goBack() {
        when {
            settings -> settings = false
            pageStack.isNotEmpty() -> page = pageStack.removeAt(pageStack.lastIndex)
            // 无来路（如通知/分享直接进来）时回它所属的主页面。
            page == MainPage.PARAMETERS -> page = MainPage.WORKFLOWS
            page == MainPage.STORAGE -> page = MainPage.ACCOUNT
            page in setOf(MainPage.CONSOLE, MainPage.TASKS) -> page = MainPage.MORE
        }
    }
    // 只在“确实有路可退”时拦返回键；主页面（栈空）不拦，交给系统退出 App。
    // 不能无条件拦：那样在首页按返回会什么都不发生，比直接退出更让人困惑。
    val parentOfCurrent: MainPage? = when (page) {
        MainPage.PARAMETERS -> MainPage.WORKFLOWS
        MainPage.STORAGE -> MainPage.ACCOUNT
        MainPage.CONSOLE, MainPage.TASKS -> MainPage.MORE
        else -> null
    }
    BackHandler(enabled = settings || pageStack.isNotEmpty() || parentOfCurrent != null) { goBack() }
    LaunchedEffect(state.navigationRequest?.id) {
        val request = state.navigationRequest ?: return@LaunchedEffect
        // 通知/导入这类外部导航也入栈，返回时能回到原页。
        pushPage(
            when (request.destination) {
                AppDestination.PARAMETERS -> MainPage.PARAMETERS
                AppDestination.RESULTS -> MainPage.RESULTS
                AppDestination.WORKFLOWS -> MainPage.WORKFLOWS
            },
        )
        viewModel.consumeNavigationRequest(request.id)
    }
    // v0.2.55：设置作为**独立层**替代整个 Scaffold（含顶栏/底栏）。
    // 不能渲染在 Scaffold 之后——那会变成盖在底栏上的浮层，两个栏都还看得见。
    if (settings) {
        SettingsScreen(
            state = state,
            viewModel = viewModel,
            snackbar = snackbar,
            onBack = { settings = false },
            onOpenStorage = {
                settings = false
                pushPage(MainPage.STORAGE)
            },
            onOpenMcp = {
                settings = false
                pushPage(MainPage.MCP)
            },
        )
    } else {
        ConnectedScaffold(
            state = state,
            viewModel = viewModel,
            snackbar = snackbar,
            page = page,
            onNavigate = { navigateTo(it) },
            onOpenSettings = { settings = true },
            onPushPage = { pushPage(it) },
            onBack = { goBack() },
            resultSource = resultSource,
            onResultSourceChange = { resultSource = it; resultAlbumId = null },
            resultLayout = resultLayout,
            onResultLayoutChange = { resultLayout = it },
            resultAlbumId = resultAlbumId,
            onResultAlbumChange = { resultAlbumId = it },
        )
    }
}

/**
 * 主界面骨架（顶栏 + 底栏 + 当前页）。
 *
 * v0.2.55：从 ConnectedApp 中抽出来，好让设置页能**整层替换**它（而不是叠在上面）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedScaffold(
    state: AppUiState,
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    page: MainPage,
    onNavigate: (MainPage) -> Unit,
    onOpenSettings: () -> Unit,
    onPushPage: (MainPage) -> Unit,
    /** v0.2.63：子页面顶栏的返回箭头走它（与系统返回键同一条路）。 */
    onBack: () -> Unit,
    resultSource: ResultSource,
    onResultSourceChange: (ResultSource) -> Unit,
    resultLayout: ResultLayout,
    onResultLayoutChange: (ResultLayout) -> Unit,
    resultAlbumId: String?,
    onResultAlbumChange: (String?) -> Unit,
) {
    /** v0.1.91：页内弹窗开关。 */
    Scaffold(
        topBar = {
            // v0.1.91：账号/控制台页显示页面标题（它们不是「连接到某台 ComfyUI」
            // 的功能页，服务器状态在账号页的连接卡片里更完整）；其余页面保持原有
            // 的服务器状态栏。这样不再出现截图里“尚未连接”顶栏套在新页面标题上的
            // 双层结构。
            TopAppBar(
                title = {
                    // v0.2.63：子页面顶栏直接显示**页名**，并由左侧返回箭头退出。
                    //
                    // 以前子页面（控制台/任务/智能助手/参数/空间管理）顶栏仍显示
                    // 「服务器状态」，页面里再自己写一个大标题——两层表头上下叠着，
                    // 屏幕上部被吃掉近 1/3，也是用户说"太拥挤"的直接来源。
                    // 现在子页面只有一层：页名 + 返回。
                    val subPageTitle: String? = when (page) {
                        MainPage.ACCOUNT -> null
                        MainPage.CONSOLE -> "控制台"
                        MainPage.STORAGE -> "空间管理"
                        MainPage.MCP -> "MCP 服务"
                        MainPage.TASKS -> "任务"
                        MainPage.PARAMETERS -> "参数"
                        else -> null
                    }
                    if (subPageTitle != null) {
                        Text(subPageTitle, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Column {
                            Text(state.activeServer?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.activeJobId != null && state.generationProgress != 1f &&
                                    state.generationMessage.isNotBlank() && !state.generationMessage.startsWith("生成失败")
                                ) {
                                    state.generationMessage
                                } else when (state.status) {
                                    ConnectionStatus.CONNECTED -> "在线 · 队列 ${state.queueRemaining} · ${state.systemStats?.comfyVersion.orEmpty()}"
                                    ConnectionStatus.RECONNECTING -> "正在重连"
                                    else -> state.connectionMessage
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    val subPageTitle: String? = when (page) {
                        MainPage.CONSOLE -> "控制台"
                        MainPage.STORAGE -> "空间管理"
                        MainPage.MCP -> "MCP 服务"
                        MainPage.TASKS -> "任务"
                        MainPage.PARAMETERS -> "参数"
                        else -> null
                    }
                    if (subPageTitle != null) {
                        // v0.2.63：子页面用返回箭头退出（父页面由 ConnectedApp 的返回栈决定）。
                        // 用项目已验证可用的 ChevronLeft，不冒险引新图标。
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ChevronLeft, "返回")
                        }
                    } else if (page != MainPage.ACCOUNT) {
                        Icon(Icons.Outlined.Wifi, null, Modifier.padding(start = 12.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                },
                actions = {
                    // ComfyUI 服务入口：圆形电脑图标（在「设置」左边）。点击向下展开
                    // 「刷新 / 连接 / 断开」——它把原来顶栏那两个旧图标（切换服务器、
                    // 重新连接）的职责一并接管了，所以下面不再单独放它们。
                    ComfyServiceChip(state, viewModel, onSwitchServer = { onNavigate(MainPage.ACCOUNT) })
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "设置") }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                // 底部栏同样半透明玻璃，并与顶栏呼应。
                containerColor = if (isSystemInDarkTheme()) {
                    Color(0xFF14181E).copy(alpha = 0.68f)
                } else {
                    Color.White.copy(alpha = 0.62f)
                },
                tonalElevation = 0.dp,
                // v0.2.63：默认 80dp 在手机上偏高，五个标签加上图标后占掉不少内容高度。
                // 收到 68dp 并把图标放到 22dp，视觉更轻、内容区更高。
                modifier = Modifier.height(68.dp),
            ) {
                MainPage.bottomBarEntries.forEach { target ->
                    NavigationBarItem(
                        // v0.2.53：参数页不在底栏，但它从工作流列表进入，
                        // 高亮「工作流」比整栏全灰更符合“我从哪儿来的”。
                        selected = page == target ||
                            (target == MainPage.WORKFLOWS && page == MainPage.PARAMETERS) ||
                            (target == MainPage.ACCOUNT && page == MainPage.STORAGE) ||
                            // 「更多」的子里页在底栏仍高亮「更多」，否则整栏全灰。
                            (target == MainPage.MORE && page in setOf(
                                MainPage.CONSOLE, MainPage.TASKS,
                            )),
                        onClick = { onNavigate(target) },
                        icon = { Icon(target.icon, null, Modifier.size(22.dp)) },
                        label = { Text(target.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            // 选中：青绿强调色 + 淡青底标；未选中：中性灰。
                            // 不显式给值的话，选中态会拿 secondaryContainer（以前是 M3
                            // 默认紫，现在已改中性灰，但直接指定更有保证）。
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // v0.2.53：页面切换改用动画期间**只组合一个页面**的写法。
            //
            // 以前用 AnimatedContent：动画期间新旧两个页面会同时组合。而这些页面
            // 本身很重（LazyColumn/LazyVerticalGrid + AsyncImage 网格 + 终端行），
            // 双份组合会把主线程压满，切页明显掉帧、不跟手——尤其从结果页/控制台
            // 这种长列表切走时。现在不再做双页面交叉淡入，只让进入的页面淡入：
            // 组合量最多少一半，切换立即响应。
            //
            // 各页面的滚动位置不丢失：LazyColumn/LazyVerticalGrid 的滚动位置会随
            // 本页离开组合而重置，所以进入时从顶部开始——这本来也是切页后的预期。
            key(page) {
                // 单页淡入：只对「刚进入的页面」做一次 140ms 透明度过渡，
                // 不对旧页面做退场动画（那会要求它继续保留在组合树里）。
                // 用 graphicsLayer 改 alpha 而不是 Modifier.alpha：后者会带着
                // 整棵子树重新创建图层，长列表下反而更贵。
                val pageAlpha = remember(page) { Animatable(0f) }
                LaunchedEffect(page) { pageAlpha.animateTo(1f, tween(140, easing = FastOutSlowInEasing)) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = pageAlpha.value },
                ) {
                    when (page) {
                        MainPage.ACCOUNT -> AccountScreen(state, viewModel)
                        MainPage.CONSOLE -> ConsoleScreen(state, viewModel)
                        MainPage.WORKFLOWS -> WorkflowScreen(state, viewModel, onOpenParameters = { onPushPage(MainPage.PARAMETERS) })
                        MainPage.PARAMETERS -> ParameterScreen(state, viewModel)
                        MainPage.MORE -> MoreScreen(
                            onOpen = { target -> onPushPage(target) },
                        )
                        MainPage.RESULTS -> ResultScreen(
                            state = state,
                            viewModel = viewModel,
                            source = resultSource,
                            onSourceChange = onResultSourceChange,
                            layout = resultLayout,
                            onLayoutChange = onResultLayoutChange,
                            selectedAlbumId = resultAlbumId,
                            onSelectedAlbumChange = onResultAlbumChange,
                        )
                        MainPage.TASKS -> TaskScreen(state, viewModel)
                        MainPage.QUICK -> QuickGenScreen(state, viewModel)
                        MainPage.STORAGE -> StorageScreen(state, viewModel)
                        MainPage.MCP -> McpScreen(state, viewModel, onBack = onBack)
                    }
                }
            }
            if (state.loading || state.generating) {
                // 必须消费掉点击事件，否则遮罩期间的触摸会穿透到底层列表，
                // 触发选中/删除工作流等误操作。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun WorkflowScreen(state: AppUiState, viewModel: MainViewModel, onOpenParameters: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var currentFolder by rememberSaveable(state.activeServer?.baseUrl) { mutableStateOf(WorkflowBrowser.ROOT) }
    var duplicateDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var deletePathTarget by remember { mutableStateOf<WorkflowEntry?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var exportRaw by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(context, uri) ?: "imported.json"
            viewModel.importWorkflow(uri, name, context.contentResolver.getType(uri))
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val raw = exportRaw
        if (uri != null && raw != null) context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(raw) }
        exportRaw = null
    }
    LaunchedEffect(state.workflows, currentFolder) {
        if (currentFolder != WorkflowBrowser.ROOT && state.workflows.none { it.isDirectory && it.path == currentFolder }) {
            currentFolder = WorkflowBrowser.ROOT
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            label = { Text("搜索工作流") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (currentFolder != WorkflowBrowser.ROOT && search.isBlank()) {
                OutlinedButton(onClick = { currentFolder = WorkflowBrowser.up(currentFolder) }) { Text("上一级") }
            }
            Text(
                if (search.isBlank()) currentFolder.removePrefix("workflows/").ifBlank { "全部分类" } else "搜索全部分类",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "image/*"))
            }) {
                Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(4.dp)); Text("打开工作流文件")
            }
        }
        Text(
            "单击工作流只预读取参数，不会跳转；双击或点击上方“打开参数”才进入参数页",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.previewWorkflow != null) {
            // 以前 6 个按钮（打开参数/新建副本/改名/移动/导出/删除）横排，手机上
            // 最后两个直接被截断（截图里「移动」只露半个字）。改成：主操作保留，
            // 其余收进溢出菜单——一行干净，也不会再被挤掉。
            var actionsExpanded by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.previewWorkflow.entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    viewModel.openPreviewedWorkflow()
                    onOpenParameters()
                }) { Text("打开参数") }
                Box {
                    IconButton(onClick = { actionsExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, "更多操作")
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("新建副本") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                actionsExpanded = false
                                dialogText = state.previewWorkflow.entry.name.substringBeforeLast('.')
                                duplicateDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("改名") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                actionsExpanded = false
                                dialogText = state.previewWorkflow.entry.name.substringBeforeLast('.')
                                renameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移动") },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            onClick = {
                                actionsExpanded = false
                                dialogText = state.previewWorkflow.entry.path.substringBeforeLast('/', "workflows")
                                moveDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出") },
                            leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            onClick = {
                                actionsExpanded = false
                                state.previewWorkflow?.let { export ->
                                    exportRaw = export.rawJson
                                    exportLauncher.launch(export.entry.name)
                                }
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                actionsExpanded = false
                                deleteDialog = true
                            },
                        )
                    }
                }
            }
        }
        val filtered = WorkflowBrowser.entries(state.workflows, currentFolder, search)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // v0.1.91：未连接时给一条明确引导。以前这里只有一片空白，
            // 用户不知道参数页在哪、也不知道下一步该干嘛。
            if (state.activeServer == null) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没连接 ComfyUI", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "先去「账号」页连接一台 ComfyUI（局域网或云端均可）。\n" +
                                    "连接后这里会列出服务器上的工作流；\n" +
                                    "选中工作流点「打开参数」即可进入参数页编辑并生成。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(filtered, key = { it.path }) { entry ->
                WorkflowRow(
                    entry = entry,
                    selected = state.previewWorkflow?.entry?.path == entry.path,
                    onClick = {
                        if (entry.isDirectory) {
                            currentFolder = entry.path
                            search = ""
                        } else {
                            viewModel.selectWorkflow(entry)
                        }
                    },
                    onDoubleClick = {
                        if (!entry.isDirectory) {
                            viewModel.selectWorkflow(entry, recordAsOpened = true)
                            onOpenParameters()
                        }
                    },
                    onDelete = if (entry.isDirectory) null else {
                        { deletePathTarget = entry }
                    },
                )
            }
        }
    }
    if (duplicateDialog) NameDialog("复制为新工作流", dialogText, { duplicateDialog = false }) { viewModel.duplicateWorkflow(it); duplicateDialog = false }
    if (renameDialog) NameDialog("工作流改名", dialogText, { renameDialog = false }) { viewModel.renameWorkflow(it); renameDialog = false }
    if (moveDialog) NameDialog("移动到文件夹", dialogText, { moveDialog = false }) { viewModel.moveWorkflow(it); moveDialog = false }
    if (deleteDialog) ConfirmDialog("删除工作流", "将从 ComfyUI 服务器永久删除 ${state.previewWorkflow?.entry?.name}。", { deleteDialog = false }) { viewModel.deleteWorkflow(); deleteDialog = false }
    deletePathTarget?.let { target ->
        // v0.2.67：文案要与实际行为一致。以前一律写"从服务器永久删除"，
        // 但未连接时删的只是本机那份（服务器根本没连上）。
        val connected = state.activeServer != null
        ConfirmDialog(
            "删除工作流",
            buildString {
                if (connected) {
                    append("将从 ComfyUI 服务器永久删除 ${target.name}")
                } else {
                    append("当前未连接服务器，将只从本机删除 ${target.name}")
                }
                append("。无法打开/识别的工作流也可以这样清理。")
            },
            { deletePathTarget = null },
        ) {
            viewModel.deleteWorkflowByPath(target.path, target.name)
            deletePathTarget = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkflowRow(
    entry: WorkflowEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    var appeared by remember { mutableStateOf(false) }
    // 列表项入场：逐个淡入 + 轻微上浮，避免整屏卡片"啪"地一次出现。
    // 只跑一次（appeared 锁住），滚动回来不会再触发。
    LaunchedEffect(Unit) { appeared = true }
    val appearAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "rowAlpha",
    )
    val appearShift by animateFloatAsState(
        targetValue = if (appeared) 0f else 24f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "rowShift",
    )
    // 液态玻璃卡：半透明底透出极光渐变，按下时轻微缩小给触感反馈。
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appearAlpha
                translationY = appearShift
            }
            .pressScale(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = { if (onDelete != null) menuExpanded = true },
            ),
        strong = true,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.FileOpen,
                    null,
                    Modifier.size(19.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.isDirectory) {
                Text(
                    formatSize(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParameterScreen(state: AppUiState, viewModel: MainViewModel) {
    val workflow = state.selectedWorkflow
    if (workflow == null) {
        ParameterHistoryScreen(state, viewModel)
        return
    }
    var expandedNodeIds by remember(workflow.entry.path) { mutableStateOf(emptySet<String>()) }
    var layoutDialog by remember { mutableStateOf(false) }
    var historyField by remember { mutableStateOf<ParameterField?>(null) }
    var uploadField by remember { mutableStateOf<ParameterField?>(null) }
    var cacheNode by remember { mutableStateOf<WorkflowNode?>(null) }
    var recentMenuExpanded by remember { mutableStateOf(false) }
    var saveAsDialog by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf("") }
    var saveAsFolder by remember(workflow.entry.path) {
        mutableStateOf(workflow.entry.path.substringBeforeLast('/', "workflows"))
    }
    var confirmGenerateWithoutLocalOutput by remember { mutableStateOf(false) }
    val multilineEditorStates = remember(workflow.entry.path) { mutableStateMapOf<String, TextFieldValue>() }
    val imageUploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val field = uploadField
        if (uri != null && field != null) viewModel.uploadField(field, uri)
        uploadField = null
    }
    val videoUploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val field = uploadField
        if (uri != null && field != null) viewModel.uploadField(field, uri)
        uploadField = null
    }
    // v0.2.53：下面这些派生量都加 remember。它们原本在每次重组时重算——参数页字段
    // 动辄几十上百条，用户在参数页滚动/改值时每次重组都重跑 filter+groupBy+sortedBy，
    // 主线程被白白占满，这是面板“不跟手”的一大来源。键取真正影响结果的输入。
    val visibleFields = remember(state.fields) { state.fields.filter { it.visible }.groupBy { it.nodeId } }
    val nodes = remember(workflow.nodes, visibleFields) {
        workflow.nodes.sortedBy { it.order }.map { node ->
            val fields = visibleFields[node.id].orEmpty().sortedBy { it.order }
            node to fields
        }
    }
    val workflowFolders = remember(state.workflows, workflow.entry.path) {
        WorkflowPath.availableFolders(state.workflows, workflow.entry.path)
    }
    val outputNodeTypes = remember(nodes) {
        nodes.asSequence().map { it.first }.filter { it.isOutput }.map { it.type }.toSet()
    }
    val hasConfiguredLocalOutput = CachePolicy.hasConfiguredOutput(
        state.cacheOutputRules,
        state.activeServer?.baseUrl,
        outputNodeTypes,
    )
    val localProblems = remember(state.fields) { FieldValidator.detailedProblems(state.fields) }
    val localProblemsByNode = remember(localProblems) {
        localProblems.groupBy { it.nodeId }.mapValues { (_, items) -> items.map { it.message } }
    }
    val problemNodeIds = remember(localProblemsByNode, state.nodeProblems) {
        localProblemsByNode.keys + state.nodeProblems.keys
    }
    val recentWorkflows = remember(state.recentWorkflowPaths, state.workflows, workflow.entry.path) {
        RecentWorkflows.resolveEntries(state.recentWorkflowPaths, state.workflows)
            .let { entries -> listOf(workflow.entry) + entries.filterNot { it.path == workflow.entry.path } }
            .distinctBy { it.path }
    }
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val suppressAutomaticRelocationUntil = remember(workflow.entry.path) { AtomicLong(0L) }
    val parameterBringIntoViewSpec = remember(workflow.entry.path, defaultBringIntoViewSpec) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (SystemClock.uptimeMillis() < suppressAutomaticRelocationUntil.get()) {
                    0f
                } else {
                    defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                }
        }
    }
    LaunchedEffect(problemNodeIds) {
        if (problemNodeIds.isNotEmpty()) expandedNodeIds = expandedNodeIds + problemNodeIds
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(0.72f)) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { recentMenuExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(workflow.entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                "${nodes.size} 个流程部件 · ${state.fields.count { it.visible }} 个参数" +
                                    if (workflow.hasUnsavedChanges) " · 本地草稿" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (workflow.hasUnsavedChanges) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                        Icon(
                            if (recentMenuExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            "选择最近打开的工作流",
                        )
                    }
                }
                DropdownMenu(
                    expanded = recentMenuExpanded,
                    onDismissRequest = { recentMenuExpanded = false },
                ) {
                    if (recentWorkflows.isEmpty()) {
                        DropdownMenuItem(text = { Text("暂无最近打开的工作流") }, onClick = {}, enabled = false)
                    } else {
                        recentWorkflows.forEach { entry ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(entry.name, maxLines = 1)
                                        Text(
                                            entry.path.substringBeforeLast('/', "workflows"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (entry.path == workflow.entry.path) Icons.Outlined.CheckCircle else Icons.Outlined.History,
                                        null,
                                    )
                                },
                                onClick = {
                                    recentMenuExpanded = false
                                    if (entry.path != workflow.entry.path) {
                                        viewModel.selectWorkflow(entry, recordAsOpened = true)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(0.28f))
            // 保存/另存分段控件：把两个动作绑进一个胶囊里，比两个并列按钮更紧凑、
            // 也更明确"它们是同一组文件操作"。中间竖线分隔 + 等分宽度。
            TextButton(onClick = { layoutDialog = true }, modifier = Modifier.height(40.dp)) { Text("表单布局") }
            Surface(
                modifier = Modifier.width(152.dp).height(40.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { viewModel.saveWorkflow() },
                        contentAlignment = Alignment.Center,
                    ) { Text("保存", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) }
                    Box(
                        Modifier.width(1.dp).fillMaxHeight(0.5f).background(MaterialTheme.colorScheme.outline),
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable {
                            saveAsName = workflow.entry.name.substringBeforeLast('.') + "-副本"
                            saveAsFolder = workflow.entry.path.substringBeforeLast('/', "workflows")
                            saveAsDialog = true
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text("另存", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
        // v0.1.89：节点缺失预检提示。放在生成状态卡片之前，用户还没点生成就能看见。
        if (state.missingNodes.isNotEmpty()) {
            MissingNodesCard(state.missingNodes)
        }
        if (state.generationMessage.isNotBlank()) {
            OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Refresh,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(state.generationMessage, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        state.activeJobId?.let { Text(it.take(8), style = MaterialTheme.typography.labelSmall) }
                    }
                    // v0.1.76：进度条只在采样阶段显示确定百分比；排队/加载模型/解码
                    // 等阶段没有 progress 消息，用不确定进度条表示"正在处理中"，避免
                    // 停在 0%/100% 造成"进度与任务不符"的错觉。
                    if (state.activeJobId != null) {
                        val progress = state.generationProgress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        // v0.1.76：页面自动重载（AI Studio 平台每十几秒一次）期间桥接在恢复，
        // 生图/高级编辑按钮会短暂灰掉。在这里给出可见解释，不再"莫名其妙"。
        // v0.1.85：① 去掉 status == CONNECTED 的限制——状态卡在"正在重连"时恰恰最需要
        // 这条提示（以前那种情况下连提示都不显示，用户只看到三个灰按钮）；
        // ② 显示真实的恢复状态文案；③ 给一个「重试」出口，别让人只能干等下一次页面加载。
        if (!state.bridgeReady && state.activeServer != null) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.connectionMessage.ifBlank { "网页正在重载，正在恢复连接…（几秒内恢复）" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.retryBridgeRecovery() }) {
                    Text("重试", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides parameterBringIntoViewSpec,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                nodes.forEach { (node, fields) ->
                    key(node.id) {
                        val nodeId = node.id
                        NodeParameterCard(
                            node = node,
                            fields = fields,
                            expanded = nodeId in expandedNodeIds,
                            active = state.currentExecutingNodeId == nodeId,
                            problems = localProblemsByNode[nodeId].orEmpty() + state.nodeProblems[nodeId].orEmpty(),
                            cached = state.cacheOutputRules.any {
                                it.enabled && it.serverUrl == state.activeServer?.baseUrl && it.nodeType == node.type
                            },
                            onToggle = {
                                expandedNodeIds = if (nodeId in expandedNodeIds) expandedNodeIds - nodeId else expandedNodeIds + nodeId
                            },
                            onLongPress = if (node.isOutput) ({ cacheNode = node }) else null,
                            viewModel = viewModel,
                            onHistory = { historyField = it },
                            onUpload = { field ->
                                uploadField = field
                                if (field.kind == ParameterKind.VIDEO) {
                                    videoUploadLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                                    )
                                } else {
                                    imageUploadLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                }
                            },
                            multilineEditorStates = multilineEditorStates,
                            onMultilineFocusGained = {
                                suppressAutomaticRelocationUntil.set(
                                    SystemClock.uptimeMillis() + IME_RELOCATION_SUPPRESSION_MILLIS,
                                )
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("出图数量", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { if (state.batchCount > 1) viewModel.setBatchCount(state.batchCount - 1) },
                enabled = !state.generating,
            ) { Icon(Icons.Outlined.Remove, "减少出图数量") }
            Text(
                "${state.batchCount}",
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { if (state.batchCount < 16) viewModel.setBatchCount(state.batchCount + 1) },
                enabled = !state.generating,
            ) { Icon(Icons.Outlined.Add, "增加出图数量") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("种子", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            SeedModeChip("随机", SeedMode.RANDOM, state.seedMode, viewModel)
            Spacer(Modifier.width(6.dp))
            SeedModeChip("固定", SeedMode.FIXED, state.seedMode, viewModel)
            Spacer(Modifier.width(6.dp))
            SeedModeChip("上一个", SeedMode.PREVIOUS, state.seedMode, viewModel)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = viewModel::openAdvancedEditor,
                modifier = Modifier.weight(1f),
                enabled = state.bridgeReady && !state.loading && !state.generating,
            ) { Text("高级编辑") }
            Button(
                onClick = {
                    if (hasConfiguredLocalOutput) viewModel.generate()
                    else confirmGenerateWithoutLocalOutput = true
                },
                enabled = !state.generating && !state.loading && state.bridgeReady && localProblems.isEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (state.generating) "生成中…" else "生成")
            }
        }
        val firstProblem = localProblems.firstOrNull()?.let { problem ->
            val title = workflow.nodes.firstOrNull { it.id == problem.nodeId }?.title ?: "节点 ${problem.nodeId}"
            "$title：${problem.message}"
        } ?: state.nodeProblems.entries.firstOrNull()?.let { (nodeId, messages) ->
            val title = workflow.nodes.firstOrNull { it.id == nodeId }?.title ?: "节点 $nodeId"
            "$title：${messages.firstOrNull().orEmpty()}"
        }
        if (firstProblem != null) {
            Text(firstProblem, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
    if (state.workflowDraftConflictRequired) {
        WorkflowDraftConflictDialog(
            message = state.workflowDraftConflictReason,
            onKeepLocal = viewModel::dismissWorkflowDraftConflict,
            onLoadServer = viewModel::discardLocalWorkflowDraft,
            onSaveAs = {
                viewModel.dismissWorkflowDraftConflict()
                saveAsName = workflow.entry.name.substringBeforeLast('.') + "-本地草稿"
                saveAsFolder = workflow.entry.path.substringBeforeLast('/', "workflows")
                saveAsDialog = true
            },
        )
    }
    if (state.workflowOverwriteRequired) {
        ConfirmDialog(
            "覆盖服务器工作流",
            state.workflowOverwriteReason,
            viewModel::dismissWorkflowOverwrite,
        ) { viewModel.saveWorkflow(force = true) }
    }
    historyField?.let { field -> PromptHistoryDialog(field, state, viewModel) { historyField = null } }
    if (layoutDialog) LayoutDialog(state.fields, viewModel) { layoutDialog = false }
    if (saveAsDialog) {
        SaveWorkflowAsDialog(
            initialName = saveAsName,
            initialFolder = saveAsFolder,
            folders = workflowFolders,
            onDismiss = { saveAsDialog = false },
        ) { name, folder ->
            viewModel.saveWorkflowAs(name, folder)
            saveAsDialog = false
        }
    }
    if (confirmGenerateWithoutLocalOutput) {
        ConfirmDialog(
            title = "尚未配置本地输出",
            message = "当前工作流没有命中任何已启用的本地保存输出。继续生成后，结果仍会保留在 ComfyUI 云端媒体资产，但不会进入手机本地结果。请长按输出部件加入白名单。是否仍然生成？",
            onDismiss = { confirmGenerateWithoutLocalOutput = false },
            confirmLabel = "仍然生成",
            onConfirm = {
                confirmGenerateWithoutLocalOutput = false
                viewModel.generate()
            },
        )
    }
    cacheNode?.let { node ->
        val cached = state.cacheOutputRules.any {
            it.serverUrl == state.activeServer?.baseUrl && it.nodeType == node.type
        }
        ConfirmDialog(
            title = if (cached) "移出全工作流保存白名单" else "加入全工作流保存白名单",
            message = if (cached) {
                "以后所有工作流中的“${node.type}”部件都不再自动保存，已经保存的文件不会删除。"
            } else {
                "以后所有工作流中的“${node.type}”输出部件都会自动保存。仅处理本 App 提交的任务，电脑浏览器提交的任务不会保存。"
            },
            onDismiss = { cacheNode = null },
            onConfirm = { viewModel.toggleCacheOutput(node); cacheNode = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeParameterCard(
    node: WorkflowNode,
    fields: List<ParameterField>,
    expanded: Boolean,
    active: Boolean,
    problems: List<String>,
    cached: Boolean,
    onToggle: () -> Unit,
    onLongPress: (() -> Unit)?,
    viewModel: MainViewModel,
    onHistory: (ParameterField) -> Unit,
    onUpload: (ParameterField) -> Unit,
    multilineEditorStates: MutableMap<String, TextFieldValue>,
    onMultilineFocusGained: () -> Unit,
) {
    val title = node.title.ifBlank { node.type.ifBlank { "未命名节点" } }
    val bringIntoViewRequester = remember(node.id) { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (active) {
            kotlinx.coroutines.delay(50)
            bringIntoViewRequester.bringIntoView()
        }
    }
    var headerHeightPx by remember(node.id) { mutableIntStateOf(0) }
    var inputMarkerHeightPx by remember(node.id) { mutableIntStateOf(0) }
    var outputMarkerHeightPx by remember(node.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val inputMarkerTop = with(density) { ((headerHeightPx - inputMarkerHeightPx).coerceAtLeast(0) / 2f).toDp() }
    val outputMarkerTop = with(density) { ((headerHeightPx - outputMarkerHeightPx).coerceAtLeast(0) / 2f).toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp)
            .bringIntoViewRequester(bringIntoViewRequester),
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                when {
                    problems.isNotEmpty() -> 2.dp
                    active -> 3.dp
                    else -> 1.dp
                },
                when {
                    problems.isNotEmpty() -> MaterialTheme.colorScheme.error
                    active -> Color(0xFF35C46A)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            ),
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { headerHeightPx = it.height }
                        .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionMarkerLabels(node.inputMarkers)
                    if (node.inputMarkers.isNotEmpty()) Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                if (node.type.isNotBlank() && node.type != title) append(node.type).append(" · ")
                                append(fields.size).append(" 个设置")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (cached) Text("本地保存白名单", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        if (node.isOutput) Text("长按管理本地保存", style = MaterialTheme.typography.labelSmall)
                        if (active) Text("正在执行", color = Color(0xFF35C46A), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开")
                    if (node.outputMarkers.isNotEmpty()) Spacer(Modifier.width(8.dp))
                    ConnectionMarkerLabels(node.outputMarkers)
                }
                if (expanded) {
                    HorizontalDivider()
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        val hasSeedActions = node.type.contains("Seed (rgthree)", ignoreCase = true)
                        if (fields.isEmpty() && !hasSeedActions) {
                            Text("此部件没有可调整参数。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (fields.isNotEmpty() || hasSeedActions) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
                            ) {
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    fields.forEachIndexed { index, field ->
                                        ParameterEditor(
                                            field,
                                            viewModel,
                                            onHistory = { onHistory(field) },
                                            onUpload = { onUpload(field) },
                                            multilineEditorStates = multilineEditorStates,
                                            onMultilineFocusGained = onMultilineFocusGained,
                                        )
                                        if (index < fields.lastIndex || hasSeedActions) HorizontalDivider()
                                    }
                                    if (hasSeedActions) {
                                        Text("种子快捷操作", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                                        FilledTonalButton(
                                            onClick = { viewModel.invokeSeedAction(node.id, "Randomize Each Time", "已设为每次生成随机") },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("每次生成随机") }
                                        FilledTonalButton(
                                            onClick = { viewModel.invokeSeedAction(node.id, "New Fixed Random", "已生成新的固定种子") },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("生成新的固定种子") }
                                        OutlinedButton(
                                            onClick = { viewModel.invokeSeedAction(node.id, "Use Last Queued Seed", "已使用上次排队种子") },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("使用上次排队种子") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ConnectionMarkerDashes(
            node.inputMarkers,
            input = true,
            onHeightChanged = { inputMarkerHeightPx = it },
            modifier = Modifier.align(Alignment.TopStart).offset(x = (-7).dp, y = inputMarkerTop),
        )
        ConnectionMarkerDashes(
            node.outputMarkers,
            input = false,
            onHeightChanged = { outputMarkerHeightPx = it },
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = outputMarkerTop),
        )
    }
}

@Composable
private fun ConnectionMarkerLabels(markers: List<WorkflowConnectionMarker>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        markers.forEach { marker ->
            Text(
                marker.label,
                color = connectionMarkerColor(marker.color),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConnectionMarkerDashes(
    markers: List<WorkflowConnectionMarker>,
    input: Boolean,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.onSizeChanged { onHeightChanged(it.height) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = if (input) Alignment.Start else Alignment.End,
    ) {
        markers.forEach { marker ->
            Box(contentAlignment = if (input) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(
                    marker.label,
                    modifier = Modifier.alpha(0f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Box(
                    Modifier.width(14.dp).height(3.dp).background(
                        connectionMarkerColor(marker.color),
                        RoundedCornerShape(2.dp),
                    ),
                )
            }
        }
    }
}

private fun connectionMarkerColor(value: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color(0xFF9E9E9E))

@Composable
private fun ParameterEditor(
    field: ParameterField,
    viewModel: MainViewModel,
    onHistory: () -> Unit,
    onUpload: () -> Unit,
    multilineEditorStates: MutableMap<String, TextFieldValue>,
    onMultilineFocusGained: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (!field.name.equals(field.label, ignoreCase = true)) {
                    Text(field.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (field.kind == ParameterKind.MULTILINE) {
                IconButton(onClick = onHistory, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.History, "历史", modifier = Modifier.size(20.dp))
                }
            }
        }
        if (field.linked) Text("已由其他部件连接，当前值只读", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
        when (field.kind) {
            ParameterKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = field.displayValue.toBoolean(), onCheckedChange = { viewModel.updateField(field.key, it.toString()) }, enabled = !field.linked)
                Spacer(Modifier.width(10.dp)); Text(if (field.displayValue.toBoolean()) "开启" else "关闭", style = MaterialTheme.typography.bodyMedium)
            }
            ParameterKind.COMBO -> ComboField(field, viewModel)
            ParameterKind.INTEGER, ParameterKind.DECIMAL -> {
                NumberField(field, viewModel)
                if (field.name.contains("seed", ignoreCase = true) && !field.nodeType.contains("Seed (rgthree)", ignoreCase = true)) {
                    FilledTonalButton(onClick = {
                        val upper = field.maximum?.toLong()?.coerceAtMost(Long.MAX_VALUE - 1) ?: Long.MAX_VALUE - 1
                        viewModel.updateField(field.key, Random.nextLong(0, upper.coerceAtLeast(1) + 1).toString())
                    }, enabled = !field.linked) { Text("随机种子") }
                }
            }
            ParameterKind.IMAGE, ParameterKind.VIDEO -> {
                OutlinedTextField(
                    field.displayValue,
                    { viewModel.updateField(field.key, it) },
                    Modifier.fillMaxWidth(),
                    enabled = !field.linked,
                    singleLine = true,
                )
                FilledTonalButton(onClick = onUpload, enabled = !field.linked) {
                    Icon(if (field.kind == ParameterKind.VIDEO) Icons.Outlined.VideoFile else Icons.Outlined.UploadFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (field.kind == ParameterKind.VIDEO) "从视频相册选择并上传" else "从相册选择并上传")
                }
            }
            ParameterKind.MULTILINE -> MultilineTextField(
                field,
                viewModel,
                multilineEditorStates,
                onFocusGained = onMultilineFocusGained,
            )
            ParameterKind.TEXT -> OutlinedTextField(
                field.displayValue,
                { viewModel.updateField(field.key, it) },
                Modifier.fillMaxWidth(),
                enabled = !field.linked,
            )
            ParameterKind.UNSUPPORTED -> Text(field.warning ?: "此控件需在高级编辑中修改", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ComboField(field: ParameterField, viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !field.linked, modifier = Modifier.fillMaxWidth()) {
            Text(field.displayValue, modifier = Modifier.weight(1f), maxLines = 1); Icon(Icons.Outlined.ArrowDownward, null)
        }
        if (expanded) {
            ComboPickerDialog(
                title = field.label.ifBlank { field.name },
                options = field.options,
                onPick = { viewModel.updateField(field.key, it); expanded = false },
                onDismiss = { expanded = false },
            )
        }
    }
}

/**
 * 下拉选项选择器（带搜索 + 虚拟化列表）。
 *
 * v0.2.43：以前用 DropdownMenu + `matches.take(100).forEach` —— DropdownMenu 不是
 * 惰性布局，一次会把 100 个菜单项全建出来；LoRA 动辄上千项时，打开就卡、滑动掉帧
 * （用户反馈"lora 数量过多浏览不够流畅"）。改成对话框 + LazyColumn：只渲染可见行，
 * 且搜索结果不设上限（滚动就能看全）。
 */
@Composable
private fun ComboPickerDialog(
    title: String,
    options: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(options, query) {
        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    // 用 Dialog + 自控宽高的 Surface，不用 AlertDialog。
    //
    // v0.2.43 初版用 AlertDialog 的 text 槽装 LazyColumn，真机上列表项被渲染错
    // （用户截图里 27 项 LoRA 大部分只显示成 "anima/"，像名字丢了）。已用真实前端
    // 核实数据源完好（该服务器 27 项均为完整路径，无裸目录项），问题在 AlertDialog
    // 文本槽的布局约束。这里自己控制宽高，彻底避开。
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索（共 ${options.size} 项）") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空") }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (matches.isEmpty()) {
                    Text(
                        "没有匹配项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    // weight(1f)：列表吃掉剩余高度，且不像 fillMaxHeight 比例那样在
                    // 对话框里可能退化成 0 高度。
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                        items(matches, key = { it }) { option ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(option) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                // 主行：去掉目录/后缀的短名（用户要认的就是这个名字）。
                                // 副行：完整路径，便于区分同名文件。两行都限宽 + 省略号。
                                Text(
                                    shortLoraName(option),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (option != shortLoraName(option)) {
                                    Text(
                                        option,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(field: ParameterField, viewModel: MainViewModel) {
    OutlinedTextField(
        value = field.displayValue,
        onValueChange = { viewModel.updateField(field.key, it) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !field.linked,
        singleLine = true,
    )
}

@Composable
private fun MultilineTextField(
    field: ParameterField,
    viewModel: MainViewModel,
    editorStates: MutableMap<String, TextFieldValue>,
    onFocusGained: () -> Unit,
) {
    var editorValue by remember(field.key) {
        mutableStateOf(
            editorStates[field.key] ?: TextFieldValue(
                text = field.displayValue,
                selection = TextRange(field.displayValue.length),
            ),
        )
    }
    var focused by remember(field.key) { mutableStateOf(false) }
    LaunchedEffect(field.displayValue, focused) {
        if (!focused && field.displayValue != editorValue.text) {
            val updated = TextFieldValue(
                text = field.displayValue,
                selection = TextRange(field.displayValue.length),
            )
            editorValue = updated
            editorStates[field.key] = updated
        }
    }
    OutlinedTextField(
        value = editorValue,
        onValueChange = { value ->
            editorValue = value
            editorStates[field.key] = value
            if (value.text != field.displayValue) viewModel.updateField(field.key, value.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .onFocusChanged {
                val gainedFocus = it.isFocused && !focused
                focused = it.isFocused
                if (gainedFocus) onFocusGained()
            },
        enabled = !field.linked,
    )
}

@Composable
private fun ParameterHistoryScreen(state: AppUiState, viewModel: MainViewModel) {
    val recentWorkflows = RecentWorkflows.resolveEntries(state.recentWorkflowPaths, state.workflows)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.History, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text("最近打开的工作流", style = MaterialTheme.typography.titleLarge)
        Text(
            "点选一项即可恢复参数；也可以回到“工作流”页选择其他文件。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (recentWorkflows.isEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(
                    "暂无历史记录，请先在工作流页打开一个工作流。",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            recentWorkflows.forEach { entry ->
                OutlinedCard(
                    Modifier.fillMaxWidth().clickable {
                        viewModel.selectWorkflow(entry, recordAsOpened = true)
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                entry.path.substringBeforeLast('/', "workflows"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "打开参数")
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptHistoryDialog(field: ParameterField, state: AppUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文本历史（${state.promptHistory.size}/50）") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索") })
                LazyColumn(Modifier.fillMaxHeight(0.6f)) {
                    items(state.promptHistory.filter { query.isBlank() || it.contains(query, true) }) { value ->
                        Row(Modifier.fillMaxWidth().clickable { viewModel.updateField(field.key, value); onDismiss() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(value, Modifier.weight(1f), maxLines = 3)
                            IconButton(onClick = { viewModel.removePromptHistory(value) }) { Icon(Icons.Outlined.Delete, "删除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = viewModel::clearPromptHistory) { Text("清空") } },
    )
}

@Composable
private fun LayoutDialog(fields: List<ParameterField>, viewModel: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参数页布局") },
        text = {
            LazyColumn(Modifier.fillMaxHeight(0.7f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(fields.sortedBy { it.order }, key = { it.key }) { field ->
                    OutlinedCard {
                        Column(Modifier.padding(10.dp)) {
                            OutlinedTextField(field.label, { viewModel.renameField(field.key, it) }, Modifier.fillMaxWidth(), label = { Text(field.name) }, singleLine = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("显示", Modifier.weight(1f)); Switch(field.visible, { viewModel.setFieldVisibility(field.key, it) })
                                IconButton(onClick = { viewModel.moveField(field.key, -1) }) { Icon(Icons.Outlined.ArrowUpward, "上移") }
                                IconButton(onClick = { viewModel.moveField(field.key, 1) }) { Icon(Icons.Outlined.ArrowDownward, "下移") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = { viewModel.setFieldSection(field.key, ParameterSection.PRIMARY) }, label = { Text("主要") }, leadingIcon = if (field.section == ParameterSection.PRIMARY) {{ Icon(Icons.Outlined.CheckCircle, null) }} else null)
                                AssistChip(onClick = { viewModel.setFieldSection(field.key, ParameterSection.MORE) }, label = { Text("更多") }, leadingIcon = if (field.section == ParameterSection.MORE) {{ Icon(Icons.Outlined.CheckCircle, null) }} else null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun ResultScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    source: ResultSource,
    onSourceChange: (ResultSource) -> Unit,
    layout: ResultLayout,
    onLayoutChange: (ResultLayout) -> Unit,
    selectedAlbumId: String?,
    onSelectedAlbumChange: (String?) -> Unit,
) {
    var selectedMedia by remember { mutableStateOf<ResultMedia?>(null) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    /** v0.2.91：只看 AI（MCP）生成的图。MCP 出图也存进本地缓存，不筛就混在一起。 */
    var onlyMcp by rememberSaveable { mutableStateOf(false) }
    val media = (if (source == ResultSource.LOCAL) state.localResults else state.results)
        .let { list -> if (onlyMcp) list.filter { it.source == ResultSource.MCP } else list }
        .sortedWith(compareByDescending<ResultMedia> { it.createdAt }.thenByDescending { it.taskNumber })
    // v0.2.53：相册分组 + 排序加 remember。结果多时（一次批量几十张）每次重组都重算
    // groupBy + 两次 sortedWith，在结果页滚动/多选时很卡。
    val albums = remember(media) {
        media.groupBy { it.jobId }
            .map { (jobId, items) -> ResultAlbum(jobId, items) }
            .sortedWith(compareByDescending<ResultAlbum> { it.media.maxOfOrNull(ResultMedia::createdAt) ?: 0L }
                .thenByDescending { it.media.maxOfOrNull(ResultMedia::taskNumber) ?: 0L })
    }
    val selectedAlbum = albums.firstOrNull { it.jobId == selectedAlbumId }
    val selectedItems = remember(media, selectedKeys) { media.filter { it.stableKey() in selectedKeys } }
    val selectionMode = selectedKeys.isNotEmpty()
    fun toggleSelection(items: Collection<ResultMedia>) {
        val keys = items.map(ResultMedia::stableKey).toSet()
        selectedKeys = if (keys.all { it in selectedKeys }) selectedKeys - keys else selectedKeys + keys
    }
    fun openMedia(item: ResultMedia, context: List<ResultMedia>) {
        if (item.kind == MediaKind.IMAGE) {
            val images = context.filter { it.kind == MediaKind.IMAGE }
            val index = images.indexOfFirst { (it.localPath ?: it.url) == (item.localPath ?: item.url) }.coerceAtLeast(0)
            viewModel.openGalleryViewer(images, index, fromResults = true)
        } else {
            selectedMedia = item
        }
    }
    LaunchedEffect(source) { selectedKeys = emptySet() }
    LaunchedEffect(media.map(ResultMedia::stableKey)) {
        selectedKeys = selectedKeys.intersect(media.map(ResultMedia::stableKey).toSet())
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source == ResultSource.LOCAL) FilledTonalButton({ onSourceChange(ResultSource.LOCAL) }, Modifier.weight(1f)) { Text("本地") }
            else OutlinedButton({ onSourceChange(ResultSource.LOCAL) }, Modifier.weight(1f)) { Text("本地") }
            if (source == ResultSource.CLOUD) FilledTonalButton({ onSourceChange(ResultSource.CLOUD) }, Modifier.weight(1f)) { Text("云端") }
            else OutlinedButton({ onSourceChange(ResultSource.CLOUD) }, Modifier.weight(1f)) { Text("云端") }
        }
        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedKeys = emptySet() }) { Icon(Icons.Outlined.Close, "退出多选") }
                Text("已选 ${selectedItems.size} 项", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { selectedKeys = media.map(ResultMedia::stableKey).toSet() }) {
                    Icon(Icons.Outlined.SelectAll, "全选")
                }
                IconButton(onClick = {
                    viewModel.saveResults(selectedItems)
                    selectedKeys = emptySet()
                }) {
                    Icon(if (source == ResultSource.CLOUD) Icons.Outlined.Download else Icons.Outlined.Save, if (source == ResultSource.CLOUD) "一键下载" else "一键保存")
                }
                if (source == ResultSource.LOCAL) {
                    IconButton(onClick = { confirmDeleteSelection = true }) { Icon(Icons.Outlined.Delete, "删除所选") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedAlbum != null) {
                    TextButton(onClick = { onSelectedAlbumChange(null) }) { Text("‹ 返回相册") }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (source == ResultSource.LOCAL) "手机独立保存的白名单作品" else "ComfyUI 服务器媒体资产",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (source == ResultSource.LOCAL) {
                            // AI 出的图也在这个列表里，给个开关分开看（默认不筛，避免改变既有习惯）。
                            TextButton(onClick = { onlyMcp = !onlyMcp }) {
                                Text(if (onlyMcp) "仅 AI 生成 ✓" else "仅 AI 生成", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = { onLayoutChange(if (layout == ResultLayout.ALL) ResultLayout.ALBUMS else ResultLayout.ALL) }) {
                            Text(if (layout == ResultLayout.ALL) "任务相册" else "全部平铺", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                IconButton(onClick = { if (source == ResultSource.LOCAL) viewModel.refreshLocalResults() else viewModel.refreshResults() }) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
            }
        }
        when {
            selectedAlbum != null -> {
                Text(albumTitle(selectedAlbum), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                ResultMediaGrid(
                    media = selectedAlbum.media,
                    selectedKeys = selectedKeys,
                    selectionMode = selectionMode,
                    onOpen = { openMedia(it, selectedAlbum.media) },
                    onToggleSelection = { toggleSelection(listOf(it)) },
                )
            }
            media.isEmpty() -> EmptyState(
                Icons.Outlined.Image,
                if (source == ResultSource.LOCAL) "暂无本地作品\n请在参数页长按输出部件加入全工作流保存白名单" else "云端暂无图片或视频",
            )
            layout == ResultLayout.ALL -> ResultMediaGrid(
                media = media,
                selectedKeys = selectedKeys,
                selectionMode = selectionMode,
                onOpen = { openMedia(it, media) },
                onToggleSelection = { toggleSelection(listOf(it)) },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(albums, key = { it.jobId }) { album ->
                    val albumKeys = album.media.map(ResultMedia::stableKey).toSet()
                    AlbumTile(
                        album = album,
                        selected = albumKeys.isNotEmpty() && albumKeys.all { it in selectedKeys },
                        partiallySelected = albumKeys.any { it in selectedKeys },
                        selectionMode = selectionMode,
                        onClick = { onSelectedAlbumChange(album.jobId) },
                        onToggleSelection = { toggleSelection(album.media) },
                    )
                }
            }
        }
    }
    selectedMedia?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedMedia = null },
            title = { Text(item.filename, maxLines = 2) },
            text = {
                if (item.kind == MediaKind.IMAGE) {
                    AsyncImage(item.url, item.filename, Modifier.fillMaxWidth().fillMaxHeight(0.72f), contentScale = ContentScale.Fit)
                } else {
                    VideoPlayer(item.url)
                }
            },
            confirmButton = { TextButton(onClick = { selectedMedia = null }) { Text("关闭") } },
            dismissButton = {
                Row {
                    IconButton(onClick = { viewModel.saveResult(item) }) { Icon(Icons.Outlined.Download, "保存到系统相册") }
                    IconButton(onClick = { viewModel.shareResult(item) }) { Icon(Icons.Outlined.Share, "分享") }
                    IconButton(onClick = { viewModel.openResult(item) }) { Icon(Icons.Outlined.FileOpen, "打开原文件") }
                }
            },
        )
    }
    if (confirmDeleteSelection) {
        ConfirmDialog(
            title = "删除所选作品",
            message = "将删除手机本地缓存中的 ${selectedItems.size} 项作品，不会删除电脑上的文件。",
            onDismiss = { confirmDeleteSelection = false },
        ) {
            viewModel.deleteLocalResults(selectedItems)
            selectedKeys = emptySet()
            confirmDeleteSelection = false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultMediaGrid(
    media: List<ResultMedia>,
    selectedKeys: Set<String>,
    selectionMode: Boolean,
    onOpen: (ResultMedia) -> Unit,
    onToggleSelection: (ResultMedia) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(105.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(media, key = { it.stableKey() }) { item ->
            val selected = item.stableKey() in selectedKeys
            Card(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection(item) else onOpen(item) },
                    onLongClick = { onToggleSelection(item) },
                ),
            ) {
                Box {
                    MediaCover(item, Modifier.fillMaxWidth().aspectRatio(1f))
                    if (selected) {
                        Box(
                            Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                        )
                        Icon(
                            Icons.Outlined.CheckCircle,
                            "已选择",
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(item.filename, Modifier.padding(6.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    album: ResultAlbum,
    selected: Boolean,
    partiallySelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val cover = album.media.firstOrNull { it.kind == MediaKind.IMAGE } ?: album.media.first()
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) onToggleSelection() else onClick() },
            onLongClick = onToggleSelection,
        ),
    ) {
        Box {
            MediaCover(cover, Modifier.fillMaxWidth().aspectRatio(1.15f))
            if (selected || partiallySelected) {
                Box(
                    Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.24f else 0.12f)),
                )
                Icon(
                    Icons.Outlined.CheckCircle,
                    if (selected) "已选择整个相册" else "已选择部分作品",
                    Modifier.align(Alignment.TopEnd).padding(7.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(albumTitle(album), maxLines = 1, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    album.media.maxOfOrNull(ResultMedia::createdAt)?.takeIf { it > 0L }?.let(::formatTime).orEmpty(),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text("${album.media.size} 项", maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MediaCover(media: ResultMedia, modifier: Modifier = Modifier) {
    if (media.kind == MediaKind.IMAGE) {
        AsyncImage(
            model = previewUrl(media),
            contentDescription = media.filename,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.VideoFile, "视频", Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun albumTitle(album: ResultAlbum): String {
    val first = album.media.first()
    return first.workflowName.ifBlank {
        if (first.taskNumber > 0L) "任务 #${first.taskNumber}" else "任务 ${album.jobId.take(8)}"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGalleryViewer(
    items: List<ResultMedia>,
    initialIndex: Int,
    /** true 表示来自「作品」页：可收藏、可删除本地缓存。批量对比结果传 false。 */
    fromResults: Boolean,
    onDismiss: () -> Unit,
    onSave: (ResultMedia, (String) -> Unit) -> Unit,
    onShare: (ResultMedia) -> Unit,
    onOpen: (ResultMedia) -> Unit,
    favoriteKeys: Set<String>,
    onFavorite: (ResultMedia) -> Unit,
    onDelete: (ResultMedia) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(items.indices)) { items.size }
    val pagerScope = rememberCoroutineScope()
    val transform = remember { GalleryTransformState() }
    // v0.1.67：把解码后的 intrinsicWidth/Height 暂存到 galleryState，让文件信息面板
    // 能直接显示「分辨率：1920 × 1080」。Key 用 stableKey（jobId+nodeId…），足够稳定。
    val galleryState = remember(items) { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    val rawCurrent = items[pagerState.currentPage.coerceIn(items.indices)]
    val resolvedSize = galleryState.value[rawCurrent.stableKey()]
    // 只有解码出尺寸后才 copy 回填；未解码时原样使用，避免不必要的对象重建。
    val current = if (resolvedSize != null) {
        rawCurrent.copy(intrinsicWidth = resolvedSize.first, intrinsicHeight = resolvedSize.second)
    } else rawCurrent
    var chromeVisible by remember { mutableStateOf(true) }
    var moreExpanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val isFavorite = current.stableKey() in favoriteKeys
    LaunchedEffect(saveFeedback, saving) {
        if (!saving && saveFeedback != null) {
            kotlinx.coroutines.delay(2_500)
            saveFeedback = null
        }
    }
    // v0.2.33：以前这里是个独立的 Dialog 窗口。在 MIUI / Android 15 上它的顶边
    // 会被钉在状态栏下方、高度却按整屏算，导致顶部露出主界面、底部操作栏被切掉
    // （前面 v0.2.29~232 反复调 DialogProperties / 窗口标志都无效，因为 Compose
    // 早已自己加好了那些标志，问题出在 Dialog 这个独立窗口本身）。
    // 现在改由根 Box 以浮层渲染，和所有其它页面共用主窗口——同一窗口的 inset
    // 行为在真机上已被验证是正确的。
    BackHandler(onBack = onDismiss)
    Surface(
        Modifier
            .fillMaxSize()
            // 浮层必须自己吃掉触摸，否则落在图片之外的点击会穿透到底下的页面。
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = Color.Black,
    ) {
            GallerySystemBars(chromeVisible)
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    ZoomableGalleryImage(
                        media = items[page],
                        transform = transform,
                        onTap = { chromeVisible = !chromeVisible },
                        onZoom = { chromeVisible = false },
                        onResolved = { size ->
                            val key = items[page].stableKey()
                            if (galleryState.value[key] != size) {
                                galleryState.value = galleryState.value + (key to size)
                            }
                        },
                    )
                }
                if (pagerState.currentPage > 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.CenterStart).padding(4.dp),
                        color = Color.Black.copy(alpha = 0.42f),
                        shape = CircleShape,
                    ) {
                        IconButton(onClick = { pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }) {
                            Icon(Icons.Outlined.ChevronLeft, "上一张", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                if (pagerState.currentPage < items.lastIndex) {
                    Surface(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(4.dp),
                        color = Color.Black.copy(alpha = 0.42f),
                        shape = CircleShape,
                    ) {
                        IconButton(onClick = { pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) {
                            Icon(Icons.Outlined.ChevronRight, "下一张", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                if (chromeVisible) {
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter)
                            // v0.1.67：完全置黑，原来 0.62 alpha 在浅色背景下能透出首页。
                            .background(Color.Black).statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(
                                current.createdAt.takeIf { it > 0L }?.let(::formatTime) ?: current.filename,
                                color = Color.White,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "单击沉浸 · 双击缩放 · 左右换图",
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text("${pagerState.currentPage + 1}/${items.size}", color = Color.White)
                    }
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                            // v0.1.67：纯黑底 + 横向滚动（小屏上图标不会被挤出屏幕）。
                            // 让出手势条/导航栏高度，避免图标被系统手势区盖住。
                            .background(Color.Black)
                            .navigationBarsPadding()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        GalleryAction(Icons.Outlined.Share, "分享") { onShare(current) }
                        GalleryAction(
                            if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            if (isFavorite) "已收藏" else "收藏",
                            tint = if (isFavorite) Color(0xFFFF5A6F) else Color.White,
                        ) { onFavorite(current) }
                        GalleryAction(
                            if (current.source == ResultSource.CLOUD) Icons.Outlined.Download else Icons.Outlined.Save,
                            if (saving) "${if (current.source == ResultSource.CLOUD) "下载" else "保存"}中" else if (current.source == ResultSource.CLOUD) "下载" else "保存",
                            enabled = !saving,
                        ) {
                            saving = true
                            saveFeedback = if (current.source == ResultSource.CLOUD) "正在下载…" else "正在保存…"
                            onSave(current) { message ->
                                saving = false
                                saveFeedback = message
                            }
                        }
                        // 删除移进「更多」菜单：云端图片不能删，它在这里只会是个灰按钮占位，
                        // 用户看了困惑（为什么点不动）；本地作品的删除入口收进菜单后，
                        // 底栏四个动作都是对当前图片“人人可用”的。
                        // 更多按钮：与其它 GalleryAction 同构（图标 + 文字），不再用
                        // weight(1f) 撑满居中——那会让它和其它图标基线不齐。
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            Column(
                                Modifier.fillMaxWidth().clickable { moreExpanded = true }.padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Icons.Outlined.MoreHoriz, "更多", tint = Color.White)
                                Text("更多", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("打开原文件") },
                                    leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                                    onClick = { moreExpanded = false; onOpen(current) },
                                )
                                DropdownMenuItem(
                                    text = { Text("文件信息") },
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                    onClick = { moreExpanded = false; showInfo = true },
                                )
                                // 只对本地作品提供删除（云端删不掉，这是下载缓存）。
                                // 批量对比结果没有删除入口（fromResults=false）。
                                if (fromResults && current.source == ResultSource.LOCAL) {
                                    DropdownMenuItem(
                                        text = { Text("删除本地缓存", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { moreExpanded = false; confirmDelete = true },
                                    )
                                }
                            }
                        }
                    }
                }
                saveFeedback?.let { message ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (chromeVisible) 72.dp else 20.dp),
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(message, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
        }
    if (confirmDelete) {
        ConfirmDialog("删除本地作品", "将从 App 本地缓存中删除 ${current.filename}，不会删除电脑上的原文件。", { confirmDelete = false }) {
            confirmDelete = false
            onDelete(current)
        }
    }
    if (showInfo) {
        // v0.1.67：长按「随机种子」可复制到剪贴板，分辨率直接展示（首次解码后由
        // ZoomableGalleryImage 回填到 ResultMedia.intrinsicWidth/intrinsicHeight）。
        val context = LocalContext.current
        var copyFeedback by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(copyFeedback) {
            if (copyFeedback != null) {
                kotlinx.coroutines.delay(2_000)
                copyFeedback = null
            }
        }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("文件信息") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(current.filename, style = MaterialTheme.typography.titleSmall)
                    // v0.1.76：耗时拆成两行——总耗时（含排队，本机记录）+ 执行耗时（服务器记录）。
                    // AI Studio 排队可能占大头，只看执行耗时会让用户觉得"生图时间不对"。
                    current.totalElapsedMs?.let { Text("总耗时（含排队）：${formatElapsed(it)}", style = MaterialTheme.typography.bodySmall) }
                    current.elapsedMs?.let { Text("执行耗时：${formatElapsed(it)}", style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Text("任务：${current.jobId}", style = MaterialTheme.typography.bodySmall)
                    current.workflowName.takeIf { it.isNotBlank() }?.let {
                        Text("工作流：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    current.seedCopyValue()?.let { seedValue ->
                        val seedColor = MaterialTheme.colorScheme.primary
                        Text(
                            "随机种子：$seedValue（长按复制）",
                            style = MaterialTheme.typography.bodySmall,
                            color = seedColor,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("seed", seedValue))
                                        copyFeedback = "已复制种子到剪贴板"
                                    },
                                )
                                .padding(vertical = 2.dp),
                        )
                    }
                    val resolution = current.resolutionLabel()
                    Text(
                        if (resolution != null) "分辨率：$resolution" else "分辨率：解码中…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "输出部件：${current.nodeTitle.ifBlank { current.nodeType.ifBlank { current.nodeId } }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // 正向提示词是最长的一段，做成长按可复制；本地作品与云端现在都有这个字段。
                    current.positivePrompt?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "正向提示词：$it（长按复制）",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("prompt", it))
                                        copyFeedback = "已复制正向提示词"
                                    },
                                )
                                .padding(vertical = 2.dp),
                        )
                    }
                    Text("来源：${if (current.source == ResultSource.LOCAL) "本地缓存" else "ComfyUI 服务器"}", style = MaterialTheme.typography.bodySmall)
                    copyFeedback?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } },
        )
    }
}

private fun formatElapsed(ms: Long): String {
    if (ms < 1000) return "${ms} 毫秒"
    val seconds = ms / 1000
    if (seconds < 60) return "$seconds 秒"
    return "${seconds / 60} 分 ${seconds % 60} 秒"
}

@Composable
private fun GallerySystemBars(chromeVisible: Boolean) {
    val view = LocalView.current
    // v0.2.33：查看器已改成主窗口内的浮层，这里直接拿 Activity 的窗口。
    val window = remember(view) { view.context.findActivity()?.window }
    LaunchedEffect(chromeVisible, window) {
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.navigationBars())
                if (chromeVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    DisposableEffect(window) {
        onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }
}

private class GalleryTransformState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

@Composable
private fun RowScope.GalleryAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).alpha(if (enabled) 1f else 0.34f)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableGalleryImage(
    media: ResultMedia,
    transform: GalleryTransformState,
    onTap: () -> Unit,
    onZoom: () -> Unit,
    onResolved: (Pair<Int, Int>) -> Unit = {},
) {
    var viewport by remember(media.localPath, media.url) { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(media.localPath, media.url) { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { viewport = it }
            .pointerInput(media.localPath, media.url, viewport, imageSize) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        onZoom()
                        if (transform.scale > 1.05f) {
                            transform.scale = 1f
                            transform.offset = Offset.Zero
                        } else {
                            val viewportAspect = viewport.width.toFloat() / viewport.height.coerceAtLeast(1)
                            val imageAspect = imageSize.width.toFloat() / imageSize.height.coerceAtLeast(1)
                            val fillWidthScale = if (imageAspect > 0f && imageAspect < viewportAspect) viewportAspect / imageAspect else 1f
                            transform.scale = (if (fillWidthScale > 1.05f) fillWidthScale else 2f).coerceIn(1f, 5f)
                            transform.offset = Offset.Zero
                        }
                    },
                )
            }.pointerInput(media.localPath, media.url) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val isMultiTouch = event.changes.count { it.pressed } > 1
                    val isMoving = pan.getDistance() > 0.5f
                    if (isMultiTouch || zoom != 1f || (transform.scale > 1f && isMoving)) {
                        if (isMultiTouch || zoom != 1f) onZoom()
                        val newScale = (transform.scale * zoom).coerceIn(1f, 5f)
                        if (newScale <= 1f) {
                            transform.offset = Offset.Zero
                        } else {
                            val maxX = viewport.width * (newScale - 1f) / 2f
                            val maxY = viewport.height * (newScale - 1f) / 2f
                            transform.offset = Offset(
                                x = (transform.offset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (transform.offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                        transform.scale = newScale
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = media.url,
            contentDescription = media.filename,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offset.x
                translationY = transform.offset.y
            },
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val w = state.result.drawable.intrinsicWidth.coerceAtLeast(1)
                val h = state.result.drawable.intrinsicHeight.coerceAtLeast(1)
                imageSize = IntSize(w, h)
                // v0.1.67：把解码尺寸上报给 GalleryViewer，让文件信息面板直接展示分辨率。
                onResolved(w to h)
            },
        )
    }
}

@Composable
private fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxWidth().height(260.dp))
}

/**
 * 空间管理页（v0.2.36）。
 *
 * 用户要的是「内存占用的详细数据」：这里分两层展示——内存（App 进程 PSS、Java 堆、
 * 设备可用/总量）与磁盘（各类文件占用），并提供逐个分区的清理入口。
 * 进入页面就自动采集一次；清理小分区后也自动重采。
 */
@Composable
private fun StorageScreen(state: AppUiState, viewModel: MainViewModel) {
    LaunchedEffect(Unit) { viewModel.refreshStorageStats() }
    var pendingClear by remember { mutableStateOf<StorageBucket?>(null) }
    val stats = state.storageStats
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("空间管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshStorageStats() }) {
                Icon(Icons.Outlined.Refresh, "重新统计")
            }
        }
        if (stats == null || state.storageLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // —— 内存 ——
            item { Text("内存", style = MaterialTheme.typography.titleMedium) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatRow("App 占用（PSS）", formatSize(stats.appPssBytes))
                        StatRow("Java 堆已用", formatSize(stats.heapUsedBytes))
                        if (stats.heapLimitBytes > 0) {
                            StatRow("Java 堆上限", formatSize(stats.heapLimitBytes))
                            val ratio = (stats.heapUsedBytes.toFloat() / stats.heapLimitBytes).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        StatRow("设备内存总量", formatSize(stats.deviceTotalBytes))
                        StatRow(
                            "设备可用内存",
                            formatSize(stats.deviceAvailableBytes),
                            tint = if (stats.lowMemory) MaterialTheme.colorScheme.error else null,
                        )
                        if (stats.lowMemory) {
                            Text(
                                "系统内存已告急，App 随时可能被系统回收",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            "PSS 是系统按内存页比例折算的 App 实际占用，比 Java 堆更能反映真实内存消耗（含 WebView 等原生内存）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // —— 磁盘 ——
            item { Text("磁盘", style = MaterialTheme.typography.titleMedium) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatRow("App 数据总量", formatSize(stats.appDataBytes))
                        Text(
                            "仅统计 App 私有目录（含数据库、缓存、WebView 数据），不含相册里保存的图片。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(stats.buckets, key = { it.label }) { bucket ->
                StorageBucketCard(bucket) {
                    if (bucket.clearable && bucket.bytes > 0L) pendingClear = bucket
                }
            }
            item {
                Text(
                    "清理只删除本机数据，不影响 AI Studio 上的项目与云端文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingClear?.let { bucket ->
        val target = storageTargetFor(bucket.label)
        if (target != null) {
            ConfirmDialog(
                title = "清理${bucket.label}",
                message = "将删除${bucket.label}，释放约 ${formatSize(bucket.bytes)}。此操作不可恢复。",
                confirmLabel = "清理",
                onDismiss = { pendingClear = null },
            ) {
                viewModel.clearStorageTarget(target)
                pendingClear = null
            }
        }
    }
}

/** 把展示用的分区标题映射回清理目标（只有可清理的分区才有）。 */
private fun storageTargetFor(label: String): StorageCleanTarget? = when (label) {
    StorageCleanTarget.LOCAL_RESULTS.label -> StorageCleanTarget.LOCAL_RESULTS
    StorageCleanTarget.WORKFLOW_SNAPSHOTS.label -> StorageCleanTarget.WORKFLOW_SNAPSHOTS
    StorageCleanTarget.WORKFLOW_DRAFTS.label -> StorageCleanTarget.WORKFLOW_DRAFTS
    StorageCleanTarget.LOGS.label -> StorageCleanTarget.LOGS
    StorageCleanTarget.CACHE_DIR.label -> StorageCleanTarget.CACHE_DIR
    else -> null
}

/** 一行「名称 —— 数值」。 */
@Composable
private fun StatRow(label: String, value: String, tint: Color? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 磁盘分区卡片：名称 + 大小 + （可清理时）清理按钮。 */
@Composable
private fun StorageBucketCard(bucket: StorageBucket, onClear: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bucket.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildList {
                        add(formatSize(bucket.bytes))
                        bucket.count?.let { add("$it 项") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (bucket.clearable) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = bucket.bytes > 0L,
                ) { Text("清理") }
            } else {
                Text(
                    "系统管理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TaskScreen(state: AppUiState, viewModel: MainViewModel) {
    var appOnly by remember { mutableStateOf(false) }
    // v0.2.53：过滤结果加 remember，避免每次重组重跑 filter。
    val jobs = remember(state.jobs, appOnly) {
        if (appOnly) state.jobs.filter { it.submittedByApp } else state.jobs
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("服务器任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("仅本 App"); Switch(appOnly, { appOnly = it })
            IconButton(onClick = viewModel::refreshTasks) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            OutlinedButton(onClick = viewModel::clearPendingJobs) { Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(4.dp)); Text("清空待执行") }
        }
        if (jobs.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.List, "暂无任务记录")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id }) { job ->
                JobCard(job, viewModel, tracked = state.activeJobId == job.id, state = state)
            }
        }
    }
}

/**
 * 快捷生图页：选一个工作流 → 只填提示词（每个文本节点一个框）→
 * 可从工作流节点参数里自由挑选想调节的参数 → 批量出图。
 */
@Composable
private fun QuickGenScreen(state: AppUiState, viewModel: MainViewModel) {
    LaunchedEffect(state.status, state.workflows) {
        viewModel.restoreQuickWorkflowIfNeeded()
    }
    var showWorkflowPicker by remember { mutableStateOf(false) }
    var showParamPicker by remember { mutableStateOf(false) }
    val quickFields = state.quickFields
    val textFields = quickFields.filter { it.kind == ParameterKind.MULTILINE }
    val enabledKeys = state.quickEnabledParams.toSet()
    val enabledFields = quickFields.filter { it.key in enabledKeys }
    val addableFields = quickFields.filter { it.kind != ParameterKind.MULTILINE && it.key !in enabledKeys }
    // v0.1.83 批量 LoRA 对比：候选来自 LoRA 下拉参数自带的 options（服务器枚举）。
    val batchRunState by viewModel.batchRun.collectAsStateWithLifecycle()
    val loraFields = quickFields.filter {
        it.kind == ParameterKind.COMBO && it.nodeType.contains("LoraLoader", ignoreCase = true)
    }
    var showBatchConfig by remember { mutableStateOf(false) }
    var showBatchResult by remember { mutableStateOf(false) }
    val batchActive = batchRunState?.phase == BatchPhase.RUNNING || batchRunState?.phase == BatchPhase.PAUSED
    val strengthRunState by viewModel.strengthRun.collectAsStateWithLifecycle()
    var showStrengthConfig by remember { mutableStateOf(false) }
    var showStrengthResult by remember { mutableStateOf(false) }
    val strengthActive = strengthRunState?.phase == StrengthPhase.RUNNING || strengthRunState?.phase == StrengthPhase.PAUSED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("快捷生图", style = MaterialTheme.typography.titleLarge)
        Text(
            "选一个工作流，只填提示词就能出图；下方可自由添加想调节的节点参数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showWorkflowPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Folder, null)
            Spacer(Modifier.width(8.dp))
            Text(
                state.quickWorkflowName ?: "选择工作流",
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(Icons.Outlined.ExpandMore, null)
        }
        if (showWorkflowPicker) {
            // DropdownMenu 锚定在按钮附近，长列表会被窗口边界压缩/裁切，
            // 尤其是工作流很多或按钮靠近底部时位置不稳定。用独立 Dialog + LazyColumn，
            // 与 LoRA 选择器保持一致：固定可控高度、可滚动、不会被页面滚动容器影响。
            Dialog(
                onDismissRequest = { showWorkflowPicker = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 560.dp),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("选择快捷工作流", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        val candidates = state.workflows.filterNot { it.isDirectory }
                        if (candidates.isEmpty()) {
                            Text(
                                "暂无可用工作流，请先在工作流页导入或连接服务器。",
                                modifier = Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                items(candidates, key = { it.path }) { entry ->
                                    TextButton(
                                        onClick = {
                                            showWorkflowPicker = false
                                            viewModel.quickSelectWorkflow(entry)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 1)
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { showWorkflowPicker = false },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("取消") }
                    }
                }
            }
        }
        if (quickFields.isEmpty()) {
            EmptyState(Icons.Outlined.PlayArrow, "先选择工作流，再填写提示词")
            return@Column
        }

        textFields.forEach { field ->
            // v0.1.88：AI 入口。快捷页没有"历史"图标位，所以做成标题行右侧的文字按钮。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.nodeTitle.ifBlank { field.nodeType },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = field.displayValue,
                onValueChange = { viewModel.quickUpdateField(field.key, it) },
                modifier = Modifier.fillMaxWidth(),
                // v0.1.69：提示词同理，粘一大段 tag 会把下面的出图数量、生图按钮
                // 全部顶出屏幕。限 6 行、超出内部滚动。
                minLines = 2,
                maxLines = 6,
                label = { Text(field.label) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("出图数量", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { if (state.batchCount > 1) viewModel.setBatchCount(state.batchCount - 1) },
                enabled = !state.generating,
            ) { Icon(Icons.Outlined.Remove, "减少出图数量") }
            Text(
                "${state.batchCount}",
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { if (state.batchCount < 16) viewModel.setBatchCount(state.batchCount + 1) },
                enabled = !state.generating,
            ) { Icon(Icons.Outlined.Add, "增加出图数量") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自定义参数", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { showParamPicker = true }, enabled = addableFields.isNotEmpty()) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加参数")
            }
        }
        if (enabledFields.isEmpty()) {
            Text(
                if (addableFields.isEmpty()) "工作流没有可调节的节点参数" else "还没添加参数 —— 点「添加参数」从工作流里挑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        enabledFields.forEach { field ->
            QuickParamRow(
                field = field,
                viewModel = viewModel,
                onRemove = { viewModel.quickRemoveParam(field.key) },
            )
        }

        if (showParamPicker) {
            AlertDialog(
                onDismissRequest = { showParamPicker = false },
                title = { Text("添加可调节参数") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(addableFields, key = { it.key }) { field ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(field.label, maxLines = 1)
                                    Text(
                                        field.nodeTitle.ifBlank { field.nodeType },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = { viewModel.quickToggleParam(field.key) }) {
                                    Icon(Icons.Outlined.Add, "添加")
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showParamPicker = false }) { Text("完成") } },
            )
        }

        // ===== v0.1.83 批量 LoRA 对比 =====
        if (loraFields.isNotEmpty() && quickFields.isNotEmpty()) {
            val batch = batchRunState
            if (batch == null) {
                OutlinedButton(
                    onClick = { showBatchConfig = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.generating && !state.loading,
                ) {
                    Icon(Icons.Outlined.Tune, null)
                    Spacer(Modifier.width(8.dp))
                    Text("批量对比：固定种子换 LoRA 逐张出图")
                }
            } else {
                BatchRunCard(
                    batch = batch,
                    viewModel = viewModel,
                    onShowResult = { showBatchResult = true },
                )
            }
        }

        // ===== v0.2.52 LoRA 强度测试（控制变量法） =====
        if (loraFields.isNotEmpty() && quickFields.isNotEmpty()) {
            val run = strengthRunState
            if (run == null) {
                OutlinedButton(
                    onClick = { showStrengthConfig = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.generating && !state.loading && !batchActive,
                ) {
                    Icon(Icons.Outlined.Tune, null)
                    Spacer(Modifier.width(8.dp))
                    Text("强度测试：LoRA 不变，只调强度找最佳值")
                }
            } else {
                StrengthRunCard(
                    run = run,
                    viewModel = viewModel,
                    onShowResult = { showStrengthResult = true },
                )
            }
        }

        Button(
            onClick = viewModel::quickGenerate,
            enabled = !state.generating && !state.loading && state.bridgeReady && !batchActive && !strengthActive,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (state.generating) "生成中…" else "快捷生成")
        }
        if (state.generating) {
            // v0.1.76：排队/加载阶段无进度消息，显示不确定进度条；采样阶段显示百分比。
            val progress = state.generationProgress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(state.generationMessage, style = MaterialTheme.typography.bodySmall)
        }
    }

    // v0.1.83：批量配置与批量结果对话框挂在 Column 外，避免被页面滚动裁剪。
    if (showBatchConfig && loraFields.isNotEmpty()) {
        BatchConfigDialog(
            state = state,
            loraFields = loraFields,
            viewModel = viewModel,
            onDismiss = { showBatchConfig = false },
        )
    }
    if (showStrengthConfig) {
        StrengthConfigDialog(
            slots = viewModel.loraStrengthSlots(),
            viewModel = viewModel,
            onDismiss = { showStrengthConfig = false },
        )
    }
    if (showStrengthResult) {
        strengthRunState?.let { run ->
            StrengthResultDialog(
                run = run,
                viewModel = viewModel,
                onDismiss = { showStrengthResult = false },
            )
        }
    }
    if (showBatchResult) {
        batchRunState?.let { batch ->
            BatchResultDialog(
                batch = batch,
                viewModel = viewModel,
                onDismiss = { showBatchResult = false },
            )
        }
    }
}

/**
 * v0.2.52 LoRA 强度测试：配置对话框（控制变量法）。
 *
 * 交互：选一个槽位 → 填 起止值 + 间距 → 实时预览会生成哪些档位、共多少张。
 * 默认只改 model 强度；节点带 strength_clip 时才允许选 clip / 两者同时。
 */
@Composable
private fun StrengthConfigDialog(
    slots: List<LoraStrengthSlot>,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    if (slots.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("LoRA 强度测试") },
            text = { Text("当前工作流里没有可调强度的 LoRA 节点（需要 LoraLoader / LoraLoaderModelOnly）。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        return
    }
    var slotIndex by remember { mutableIntStateOf(0) }
    val slot = slots[slotIndex.coerceIn(slots.indices)]
    var target by remember(slot.nodeId) { mutableStateOf(LoraStrengthTarget.MODEL) }
    var startText by remember(slot.nodeId) { mutableStateOf("0.1") }
    var endText by remember(slot.nodeId) { mutableStateOf("1.0") }
    var stepText by remember(slot.nodeId) { mutableStateOf("0.1") }

    val start = startText.trim().toDoubleOrNull()
    val end = endText.trim().toDoubleOrNull()
    val step = stepText.trim().toDoubleOrNull()
    val strengths = if (start != null && end != null && step != null) {
        LoraStrengthMatrix.expandSteps(start, end, step)
    } else {
        emptyList()
    }
    val overLimit = LoraStrengthMatrix.exceedsLimit(strengths.size)
    val invalid = strengths.isEmpty() || overLimit
    val hasClip = slot.clipFieldKey != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LoRA 强度测试") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "固定当前 LoRA 组合与其它参数，只改一个 LoRA 的强度逐张出图（控制变量法）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (slots.size > 1) {
                    Text("选择要测试的 LoRA（共 ${slots.size} 个）", style = MaterialTheme.typography.labelMedium)
                    var slotExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { slotExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(shortLoraName(slot.displayName), modifier = Modifier.weight(1f), maxLines = 1)
                            Icon(Icons.Outlined.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = slotExpanded, onDismissRequest = { slotExpanded = false }) {
                            slots.forEachIndexed { index, item ->
                                DropdownMenuItem(
                                    text = { Text(shortLoraName(item.displayName), maxLines = 1) },
                                    onClick = { slotIndex = index; slotExpanded = false },
                                )
                            }
                        }
                    }
                } else {
                    Text("将测试：${shortLoraName(slot.displayName)}", style = MaterialTheme.typography.labelMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("起始") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("结束") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = stepText,
                        onValueChange = { stepText = it },
                        label = { Text("间距") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("测试范围", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StrengthTargetChip("model", LoraStrengthTarget.MODEL, target) { target = it }
                    if (hasClip) {
                        StrengthTargetChip("clip", LoraStrengthTarget.CLIP, target) { target = it }
                        StrengthTargetChip("两者", LoraStrengthTarget.BOTH, target) { target = it }
                    }
                }
                when {
                    start == null || end == null || step == null ->
                        Text("请填入有效数字", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    strengths.isEmpty() ->
                        Text(
                            "区间无效：间距需大于 0、结束值不小于起始值，且一次不超过 ${LoraStrengthMatrix.MAX_STEPS} 档",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    overLimit ->
                        Text(
                            "会生成 ${strengths.size} 张，超过单次上限 ${LoraStrengthMatrix.MAX_TASKS} 张。请收窄区间或加大间距。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    else ->
                        Text(
                            "将生成 ${strengths.size} 张：${LoraStrengthMatrix.describeSteps(strengths)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                Text(
                    "其它 LoRA 与提示词、种子等参数全部保持不变，跑完自动恢复原强度。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !invalid,
                onClick = {
                    if (viewModel.startStrengthMatrix(slot.nodeId, target, strengths)) onDismiss()
                },
            ) { Text("开始测试") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StrengthTargetChip(
    label: String,
    value: LoraStrengthTarget,
    current: LoraStrengthTarget,
    onSelect: (LoraStrengthTarget) -> Unit,
) {
    val selected = value == current
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    if (selected) {
        FilledTonalButton(onClick = {}, enabled = false, contentPadding = padding) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = { onSelect(value) }, contentPadding = padding) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** LoRA 强度测试：进行中/已结束的卡片。 */
@Composable
private fun StrengthRunCard(run: LoraStrengthRun, viewModel: MainViewModel, onShowResult: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "强度测试 · ${shortLoraName(run.slotTitle)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (run.phase == StrengthPhase.DONE || run.phase == StrengthPhase.CANCELLED) {
                    IconButton(onClick = viewModel::dismissStrengthRun) { Icon(Icons.Outlined.Close, "收起强度测试卡片") }
                }
            }
            val running = run.phase == StrengthPhase.RUNNING || run.phase == StrengthPhase.PAUSED
            if (running) {
                LinearProgressIndicator(
                    progress = { run.finished.toFloat() / run.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${run.finished}/${run.total} · ${run.current?.label ?: "等待提交…"} · 种子=${run.seed.take(12)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    if (run.phase == StrengthPhase.RUNNING) {
                        TextButton(onClick = viewModel::pauseStrengthRun) { Text("暂停") }
                    } else {
                        TextButton(onClick = viewModel::resumeStrengthRun) { Text("继续") }
                    }
                    TextButton(onClick = viewModel::cancelStrengthRun) { Text("取消") }
                }
            } else {
                Text(
                    run.message.ifBlank { if (run.phase == StrengthPhase.CANCELLED) "已取消" else "测试结束" },
                    style = MaterialTheme.typography.bodySmall,
                )
                val mediaCount = run.items.sumOf { it.media.size }
                Row {
                    if (mediaCount > 0) {
                        TextButton(onClick = onShowResult) { Text("查看 $mediaCount 张图") }
                    }
                    TextButton(onClick = viewModel::dismissStrengthRun) { Text("收起") }
                }
            }
            run.items.takeLast(6).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.success) Icons.Outlined.CheckCircle else Icons.Outlined.Close,
                        null,
                        Modifier.size(16.dp),
                        tint = if (item.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.label +
                            (item.message.takeIf { m -> !item.success && m.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** 强度测试结果：按强度排序平铺，每张标注强度值。 */
@Composable
private fun StrengthResultDialog(run: LoraStrengthRun, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val media = run.items.sortedBy { it.strength }.flatMap { item ->
        item.media.map { item to it }
    }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(viewerIndex) {
        val index = viewerIndex ?: return@LaunchedEffect
        viewerIndex = null
        onDismiss()
        viewModel.openGalleryViewer(media.map { it.second }, index, fromResults = false)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("强度测试结果（${media.size} 张）") },
        text = {
            if (media.isEmpty()) {
                Text("这批没有可展示的图片", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    gridItemsIndexed(media, key = { _, pair -> pair.second.stableKey() }) { index, pair ->
                        Column(
                            Modifier.fillMaxWidth().clickable { viewerIndex = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                pair.second.url,
                                pair.first.label,
                                Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                LoraStrengthMatrix.formatStrength(pair.first.strength),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * v0.1.83 批量 LoRA 对比：进行中/已结束的批次卡片。
 */
@Composable
private fun BatchRunCard(batch: BatchRun, viewModel: MainViewModel, onShowResult: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "批量对比 · ${batch.workflowName}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (batch.phase == BatchPhase.DONE || batch.phase == BatchPhase.CANCELLED) {
                    IconButton(onClick = viewModel::dismissBatch) { Icon(Icons.Outlined.Close, "收起批量卡片") }
                }
            }
            val running = batch.phase == BatchPhase.RUNNING || batch.phase == BatchPhase.PAUSED
            if (running) {
                LinearProgressIndicator(
                    progress = { batch.finished.toFloat() / batch.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val currentLabel = batch.current?.let { "正在跑 ${shortLoraName(it)}" } ?: "等待提交…"
                Text(
                    "${batch.finished}/${batch.total} · $currentLabel · 种子=${batch.seed.take(12)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    if (batch.phase == BatchPhase.RUNNING) {
                        TextButton(onClick = viewModel::pauseBatch) { Text("暂停") }
                    } else {
                        TextButton(onClick = viewModel::resumeBatch) { Text("继续") }
                    }
                    TextButton(onClick = viewModel::cancelBatch) { Text("取消") }
                }
            } else {
                Text(
                    batch.message.ifBlank { if (batch.phase == BatchPhase.CANCELLED) "批量已取消" else "批量结束" },
                    style = MaterialTheme.typography.bodySmall,
                )
                val mediaCount = batch.items.sumOf { it.media.size }
                Row {
                    if (mediaCount > 0) {
                        TextButton(onClick = onShowResult) { Text("查看 $mediaCount 张图") }
                    }
                    TextButton(onClick = viewModel::dismissBatch) { Text("收起") }
                }
            }
            // 最近完成的几张（含失败原因），一眼看到跑挂了哪个 LoRA。
            batch.items.takeLast(6).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.success) Icons.Outlined.CheckCircle else Icons.Outlined.Close,
                        null,
                        Modifier.size(16.dp),
                        tint = if (item.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        shortLoraName(item.loraName) +
                            (item.message.takeIf { m -> !item.success && m.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * 批量配置：选变量槽（多 LoRA 工作流时）、勾选候选 LoRA、看预估耗时。
 * 疑似与 checkpoint 基模型不匹配的候选默认不勾选（黄色提示，可强制勾选）。
 */
@Composable
private fun BatchConfigDialog(
    state: AppUiState,
    loraFields: List<ParameterField>,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    var slotIndex by remember { mutableIntStateOf(0) }
    val slot = loraFields[slotIndex.coerceIn(loraFields.indices)]
    val checkpointName = state.quickFields.firstOrNull {
        it.kind == ParameterKind.COMBO &&
            (it.nodeType.contains("CheckpointLoader", ignoreCase = true) ||
                it.name.equals("ckpt_name", ignoreCase = true) ||
                it.name.equals("unet_name", ignoreCase = true))
    }?.displayValue
    val candidates = slot.options.ifEmpty { listOf(slot.displayValue) }
    var selected by remember(slot.key) {
        mutableStateOf(candidates.filterNot { BatchCompareLogic.suspectIncompatible(checkpointName, it) }.toSet())
    }
    val durations = state.jobs.filter { it.submittedByApp && it.state == JobState.SUCCESS }.mapNotNull { it.durationMillis }
    val avgMillis = if (durations.isEmpty()) null else durations.average().toLong()
    val estimate = BatchCompareLogic.estimateMinutes(selected.size, avgMillis)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量 LoRA 对比") },
        text = {
            Column {
                if (loraFields.size > 1) {
                    Text(
                        "工作流有 ${loraFields.size} 个 LoRA 节点，先选本轮要轮换的（其余保持当前值）：",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    var slotExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { slotExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${slot.nodeTitle.ifBlank { slot.nodeType }} · ${slot.label}",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Icon(Icons.Outlined.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = slotExpanded, onDismissRequest = { slotExpanded = false }) {
                            loraFields.forEachIndexed { index, field ->
                                DropdownMenuItem(
                                    text = { Text("${field.nodeTitle.ifBlank { field.nodeType }} · ${field.label}", maxLines = 1) },
                                    onClick = { slotIndex = index; slotExpanded = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "固定当前种子与提示词，只轮换 LoRA 逐张提交。黄色为疑似基模型不匹配（默认不勾，可强制勾选）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { selected = candidates.filterNot { BatchCompareLogic.suspectIncompatible(checkpointName, it) }.toSet() }) {
                        Text("选推荐的")
                    }
                    TextButton(onClick = { selected = candidates.toSet() }) { Text("全选") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                }
                // v0.2.43：加搜索。LoRA 上千项时逐项翻找不现实（用户反馈"没有搜索功能"）。
                var loraQuery by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = loraQuery,
                    onValueChange = { loraQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索 LoRA（共 ${candidates.size} 项）") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                )
                // 预先算好"疑似不匹配"集合，别在每行里重复算（那才是滑动掉帧的真因）。
                val suspectNames = remember(candidates, checkpointName) {
                    candidates.filter { BatchCompareLogic.suspectIncompatible(checkpointName, it) }.toSet()
                }
                val shown = remember(candidates, loraQuery) {
                    if (loraQuery.isBlank()) candidates else candidates.filter { it.contains(loraQuery, ignoreCase = true) }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it }) { name ->
                        val suspect = name in suspectNames
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = name in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + name else selected - name
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(shortLoraName(name), maxLines = 1)
                                if (suspect) {
                                    Text(
                                        "⚠ 疑似与 ${checkpointName?.let(::shortLoraName) ?: "当前模型"} 不匹配",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "已选 ${selected.size}/${BatchCompareLogic.MAX_CANDIDATES} 张" +
                        (estimate?.let { " · 预计约 $it 分钟" } ?: " · 暂无历史耗时，无法预估"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    // 按 candidates 的顺序提交，保持与列表展示一致
                    viewModel.startBatchCompare(slot.key, candidates.filter { it in selected })
                    onDismiss()
                },
            ) { Text("开始批量") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 批量结果网格：同一种子不同 LoRA 并排对比，每张标注 LoRA 名。
 * 点开任意一张进入全屏画廊（复用结果页查看器）。
 */
@Composable
private fun BatchResultDialog(batch: BatchRun, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val media = batch.items.flatMap { it.media }
    val loraByStableKey = buildMap {
        batch.items.forEach { item ->
            item.media.forEach { m -> put(m.stableKey(), item.loraName) }
        }
    }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // 打开全屏查看器前先关掉本对话框：它是独立窗口，会盖住主窗口里渲染的查看器。
    LaunchedEffect(viewerIndex) {
        val index = viewerIndex ?: return@LaunchedEffect
        viewerIndex = null
        onDismiss()
        viewModel.openGalleryViewer(media, index, fromResults = false)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量对比结果（${media.size} 张）") },
        text = {
            if (media.isEmpty()) {
                Text("这批没有可展示的图片", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    gridItemsIndexed(media, key = { _, item -> item.stableKey() }) { index, item ->
                        Column(
                            Modifier.fillMaxWidth().clickable { viewerIndex = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                item.url,
                                loraByStableKey[item.stableKey()] ?: item.filename,
                                Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                loraByStableKey[item.stableKey()]?.let(::shortLoraName) ?: item.filename,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** LoRA 文件名展示：去目录与扩展名。 */
private fun shortLoraName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
        .removeSuffix(".safetensors").removeSuffix(".sft").removeSuffix(".ckpt").removeSuffix(".pt")

@Composable
private fun SeedModeChip(label: String, mode: SeedMode, current: SeedMode, viewModel: MainViewModel) {
    val selected = mode == current
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    if (selected) {
        FilledTonalButton(
            onClick = {},
            enabled = false,
            contentPadding = padding,
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    } else {
        OutlinedButton(
            onClick = { viewModel.setSeedMode(mode) },
            contentPadding = padding,
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun QuickParamRow(
    field: ParameterField,
    viewModel: MainViewModel,
    onRemove: (() -> Unit)? = null,
) {
    val isSeed = field.name.contains("seed", ignoreCase = true)
    when (field.kind) {
        ParameterKind.COMBO -> {
            var expanded by remember { mutableStateOf(false) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${field.nodeTitle.ifBlank { field.nodeType }} · ${field.label}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    onRemove?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Outlined.Close, "移除参数")
                        }
                    }
                }
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(field.displayValue, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Outlined.ArrowDropDown, null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.quickUpdateField(field.key, option)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        ParameterKind.BOOLEAN -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, modifier = Modifier.weight(1f))
                onRemove?.let {
                    IconButton(onClick = it) { Icon(Icons.Outlined.Close, "移除参数") }
                }
                Switch(
                    field.displayValue.equals("true", ignoreCase = true),
                    { viewModel.quickUpdateField(field.key, if (it) "true" else "false") },
                )
            }
        }
        ParameterKind.INTEGER, ParameterKind.DECIMAL -> {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, modifier = Modifier.weight(1f))
                    onRemove?.let {
                        IconButton(onClick = it) { Icon(Icons.Outlined.Close, "移除参数") }
                    }
                    if (isSeed) {
                        TextButton(onClick = { viewModel.quickUpdateField(field.key, Math.abs(Random.nextLong()).toString()) }) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("随机")
                        }
                    }
                }
                OutlinedTextField(
                    value = field.displayValue,
                    onValueChange = { newValue ->
                        val valid = if (field.kind == ParameterKind.INTEGER) {
                            newValue.isEmpty() || newValue.all { it.isDigit() || it == '-' }
                        } else {
                            newValue.isEmpty() || newValue.toDoubleOrNull() != null
                        }
                        if (valid) viewModel.quickUpdateField(field.key, newValue)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        else -> {
            // IMAGE / VIDEO / UNSUPPORTED 等：快捷页不提供编辑，保持工作流原值。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, modifier = Modifier.weight(1f))
                Text(
                    "保持工作流原值",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JobCard(
    job: JobSummary,
    viewModel: MainViewModel,
    tracked: Boolean,
    state: AppUiState,
) {
    val trackable = job.state in setOf(JobState.RUNNING, JobState.PENDING)
    // 已用时间只在跟踪本任务时有意义（generationStartedAt 属于当前跟踪的那个）。
    val elapsed = if (tracked) {
        state.generationStartedAt?.let { System.currentTimeMillis() - it }
    } else null
    val node = job.currentNode?.takeIf { it.isNotBlank() }
    val percent = job.progress?.let { (it * 100).toInt() }?.takeIf { it > 0 }
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = trackable) { viewModel.takeoverJob(job) },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.id.take(12), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    when (job.state) {
                        JobState.RUNNING -> "运行中"
                        JobState.PENDING -> "等待中"
                        JobState.SUCCESS -> "成功"
                        JobState.ERROR -> "失败"
                        JobState.CANCELLED -> "已取消"
                        JobState.UNKNOWN -> "历史"
                    },
                    color = if (job.state == JobState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            if (tracked) {
                // v0.2.90：节点名 + 已用时间为主，百分比降级为辅助。反代下百分比
                // 经常丢失且不可补发，节点名却能被重连补发救回来。
                Text(
                    JobProgressText.compact(node, elapsed, percent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (trackable) {
                Text("点击接管此任务并打开对应工作流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            job.workflowName.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            job.durationMillis?.takeIf { it > 0 }?.let {
                Text("耗时：${formatDuration(it)}", style = MaterialTheme.typography.labelSmall)
            }
            if (job.submittedByApp) Text("本 App 提交", style = MaterialTheme.typography.labelSmall)
            // v0.2.95：跟踪中时节点名已在上面的 compact 文案里出现过，这里再显示一次
            // 就是同一信息重复两行。只在未跟踪（接管前）时才单独给节点名。
            if (!tracked) {
                job.currentNode?.let { Text("节点：$it", style = MaterialTheme.typography.bodySmall) }
            }
            // 失败时把真实原因显示出来（v0.2.43）。以前任务列表只说"失败"，
            // 用户看不出是显存不够、模型缺失还是参数错。
            job.message.takeIf { it.isNotBlank() && job.state != JobState.SUCCESS }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.state == JobState.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v0.2.95：只有"确实知道当前进度"才画确定进度条。反代下百分比可能已在
            // 断线期间过期（ComfyUI 重连不补发），一个陈旧的数字比转圈更误导——
            // 用户会以为卡在某个百分比上。PENDING 也用不确定：它还没开始跑。
            val determinate = job.state == JobState.RUNNING && job.progress != null
            if (determinate) {
                LinearProgressIndicator(
                    progress = { job.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else if (job.state in setOf(JobState.RUNNING, JobState.PENDING)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (trackable) {
                TextButton(onClick = { viewModel.cancelJob(job) }, modifier = Modifier.align(Alignment.End)) { Text("取消任务") }
            }
        }
    }
}

/**
 * v0.2.88：MCP 服务独立页。
 *
 * 设置页原来塞的那几行不够用了：这个功能的可用性取决于三层（服务在跑 → ComfyUI
 * 连着 → AiCode 配置对），页面上要把三层状态并排摆出来，断在哪一层一眼可见；
 * 再配上调用日志（排查"AI 说它调了但没反应"的唯一手段）与诊断自检。
 *
 * 端口等易变项收在"高级"折叠区——主界面只留日常要看的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    var portDraft by remember(state.mcpServerConfiguredPort) {
        mutableStateOf(
            (state.mcpServerConfiguredPort.takeIf { it != 0 } ?: McpServerManager.DEFAULT_PORT).toString(),
        )
    }
    var diagnosing by remember { mutableStateOf(false) }
    var diagnoseResult by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var logTick by remember { mutableStateOf(0) } // 日志是内存态，用 tick 驱动重组

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ChevronLeft, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== 状态区 =====
            SettingsSection("状态", icon = Icons.Outlined.Extension) {
                SettingsToggleRow(
                    // v0.2.97：标题叫"服务开关"——页面顶栏已经是"MCP 服务"，
                    // 这里再写一次会出现两个同名标题（用户截图确认过）。
                    title = "服务开关",
                    subtitle = when {
                        state.mcpServerEnabled && state.mcpServerPort > 0 ->
                            "监听中：127.0.0.1:${state.mcpServerPort}"
                        state.mcpServerEnabled -> "正在启动…"
                        else -> "已关闭 —— 开启后同机 AiCode 可操作本 App 连着的 ComfyUI"
                    },
                    checked = state.mcpServerEnabled,
                    onCheckedChange = viewModel::setMcpServerEnabled,
                )
                // 第二层：ComfyUI 连接状态。服务活着但 ComfyUI 断着，是"AI 报错"
                // 的最常见原因，两层并排才能一眼看出断在哪。
                //
                // v0.2.97：不再把一切非 CONNECTED 都归成"请回工作流页连接服务器"。
                // 不可用至少有四种原因，**照那句话做往往完全没用**（项目根本没启动时，
                // 让用户去刷新 Cookie 全是白费）。按原因给不同的行动指引。
                val studio = state.aiStudio
                val availability = ComfyAvailability.resolve(
                    connected = state.status == ConnectionStatus.CONNECTED,
                    hasServer = !state.activeServer?.baseUrl.isNullOrBlank(),
                    connecting = state.status == ConnectionStatus.CONNECTING ||
                        state.status == ConnectionStatus.RECONNECTING,
                    loginExpired = state.cookieExpired,
                    runningProjects = studio.projects.count { it.running },
                    startingProjects = studio.projects.count { it.running } +
                        listOfNotNull(studio.startingProjectId).size,
                    environmentReady = studio.environmentReadyProjectId != null,
                )
                val comfyStatus = ComfyAvailability.describe(availability, state.activeServer?.name.orEmpty())
                Text(
                    "ComfyUI：$comfyStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (availability == ComfyAvailability.Reason.READY) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                // 第三层：最近调用。服务在跑、ComfyUI 在线，但 AI 半天没动静时看这里。
                val last = if (state.mcpServerEnabled) McpCallLog.last() else null
                Text(
                    if (last != null) {
                        "最近调用：${McpTime.format(last.timestamp)} · ${last.summary} · ${if (last.ok) "成功" else "失败"}"
                    } else {
                        "最近调用：尚无"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ===== AiCode 配置 =====
            val token = state.mcpServerToken
            if (token.isNotBlank()) {
                SettingsSection("AiCode 配置", icon = Icons.Outlined.ContentCopy) {
                    val actualPort = state.mcpServerPort.takeIf { it > 0 } ?: effectivePort(state)
                    val snippet = McpServerManager.configSnippet(token, actualPort, state.mcpServerRequireAuth)
                    // v0.2.97（F2）：默认收拢。设置页已经很长，配置块不常改。
                    var configExpanded by rememberSaveable { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable { configExpanded = !configExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.mcpServerRequireAuth) "已开启鉴权（需 token）" else "免鉴权（本地服务）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("AiCode MCP 配置", snippet))
                            android.widget.Toast.makeText(context, "已复制配置", Toast.LENGTH_SHORT).show()
                        }) { Text("复制全部") }
                        Icon(
                            if (configExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null,
                        )
                    }
                    if (configExpanded) {
                        // 分步展示：用户知道自己在配什么、哪一步出错了。
                        Text(
                            "第 1 步 · URL：\nhttp://127.0.0.1:$actualPort/mcp",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        if (state.mcpServerRequireAuth) {
                            Text(
                                "第 2 步 · Token（已打码，点「显示」查看）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            var tokenVisible by rememberSaveable { mutableStateOf(false) }
                            Text(
                                if (tokenVisible) token else "•".repeat(token.length.coerceAtMost(32)),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Text(if (tokenVisible) "隐藏" else "显示")
                                }
                                TextButton(onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("MCP token", token))
                                    android.widget.Toast.makeText(context, "已复制 token", Toast.LENGTH_SHORT).show()
                                }) { Text("复制 token") }
                                TextButton(onClick = viewModel::regenerateMcpToken) { Text("重新生成") }
                            }
                        }
                        SelectionContainer {
                            Text(
                                snippet,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                            )
                        }
                    }
                    // 开启鉴权（F1 默认关）。
                    SettingsToggleRow(
                        title = "要求鉴权（Bearer token）",
                        subtitle = if (state.mcpServerRequireAuth) {
                            "已开启：AiCode 必须带 token 才能调用"
                        } else {
                            "默认关闭。仅绑 127.0.0.1，本机其他 App 可访问；想更严可以打开"
                        },
                        checked = state.mcpServerRequireAuth,
                        onCheckedChange = viewModel::setMcpServerRequireAuth,
                    )
                    Text(
                        "⚠ 改完配置需在 AiCode 里新开一次会话才生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ===== 工具 =====
            SettingsSection("工具（${McpToolRegistry.TOOL_NAMES.size}）", icon = Icons.AutoMirrored.Outlined.List) {
                McpToolRegistry.TOOL_NAMES.forEach { tool ->
                    val description = when (tool) {
                        "list_models" -> "列出服务器上的模型与 LoRA"
                        "list_workflows" -> "列出工作流（标注是否 API 格式）"
                        "describe_workflow" -> "查看工作流里可调的参数（steps/cfg/尺寸/种子…）"
                        "generate" -> "用工作流出图（结果同时存入结果页，标为 AI 生成）"
                        "job_status" -> "查询任务进度（queued/running/done/failed，可一次查多个）"
                        "cancel_jobs" -> "紧急刹车：中止任务或清空队列"
                        else -> ""
                    }
                    Text(tool, style = MaterialTheme.typography.bodyMedium)
                    if (description.isNotBlank()) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "未来的 terminal 工具（让 AI 执行命令）会在这里出现，默认关闭。" +
                        "紧急停止也在常驻通知里 —— AI 批量出图时可一键清队列并停服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ===== 调用日志 =====
            SettingsSection("调用日志", icon = Icons.Outlined.FileOpen) {
                key(logTick) {
                    val entries = McpCallLog.snapshot()
                    if (entries.isEmpty()) {
                        Text(
                            "暂无调用。AiCode 每次连上、调用工具都会记在这里（最多 50 条，不落盘）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        entries.asReversed().forEach { entry ->
                            Text(
                                "${McpTime.format(entry.timestamp)}  ${entry.summary}  ${if (entry.ok) "✓" else "✗"} ${entry.detail}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (entry.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val text = McpCallLog.snapshot().joinToString("\n") { e ->
                            "${McpTime.format(e.timestamp)}  ${e.summary}  ${if (e.ok) "OK" else "FAIL"} ${e.detail}"
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("MCP 调用日志", text))
                        android.widget.Toast.makeText(context, "已复制日志", Toast.LENGTH_SHORT).show()
                        logTick++
                    }) { Text("复制") }
                    TextButton(onClick = {
                        McpCallLog.clear()
                        logTick++
                    }) { Text("清空") }
                    TextButton(onClick = { logTick++ }) { Text("刷新") }
                }
            }

            // ===== 诊断 =====
            SettingsSection("诊断", icon = Icons.Outlined.Info) {
                OutlinedButton(
                    onClick = {
                        diagnosing = true
                        scope.launch {
                            diagnoseResult = viewModel.runMcpDiagnostics()
                            diagnosing = false
                            logTick++
                        }
                    },
                    enabled = !diagnosing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (diagnosing) "自检中…" else "开始自检")
                }
                diagnoseResult.forEach { (name, ok) ->
                    Text(
                        "${if (ok) "✓" else "✗"} $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ===== 高级 =====
            Row(
                Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("高级", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (showAdvanced) {
                SettingsSection("端口", icon = Icons.Outlined.Edit) {
                    Text(
                        "默认 ${McpServerManager.DEFAULT_PORT}。一般不用改；改了要同步更新 AiCode 配置并新开会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = portDraft,
                            onValueChange = { portDraft = it.filter(Char::isDigit).take(5) },
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                        )
                        TextButton(onClick = { portDraft = McpServerManager.DEFAULT_PORT.toString() }) { Text("恢复默认") }
                        TextButton(onClick = { portDraft.toIntOrNull()?.let(viewModel::setMcpServerPort) }) { Text("保存") }
                    }
                    Text(
                        "端口被其他应用占用时服务会启动失败并明确提示，不会静默换端口（那会让 AiCode 配置悄悄失效）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 端口展示用：配置了用配置，否则默认。 */
private fun effectivePort(state: AppUiState): Int =
    state.mcpServerConfiguredPort.takeIf { it != 0 } ?: McpServerManager.DEFAULT_PORT

/** 时间格式化（日志用）：HH:mm:ss。 */
private object McpTime {
    fun format(timestamp: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(timestamp))
}

/**
 * v0.1.89：节点缺失预检的提示卡片。
 *
 * 刻意做成"警告"而不是"阻断"——有些节点存在于运行时却不在 /object_info 里
 * （老的 Note、部分前端专属节点），一刀切禁掉生成会误伤。这里只负责把话讲清楚：
 * 缺什么、会怎样、该去装什么。用户自己判断要不要提交。
 */
@Composable
private fun MissingNodesCard(missing: List<String>) {
    OutlinedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(
                    "这个工作流缺少 ${missing.size} 个节点",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                missing.take(8).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (missing.size > 8) {
                Text("…另外还有 ${missing.size - 8} 个", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "这些节点当前 ComfyUI 里没有，直接生成会在服务器端报错。" +
                    "请安装对应的自定义节点包后重启 ComfyUI，再重新连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 设置页的一个分组。
 *
 * 设置项以前是一条条线性往下堆（截图里一屏堆了 8 组，只能靠横线分隔，
 * 分不清哪里到哪里）。这里改成「一张玻璃卡 = 一个分组」：标题在卡内、
 * 左侧一条强调竖条，卡片之间留 10dp——层级一眼可辨，也贴合液态玻璃的观感。
 *
 * v0.2.59：竖条改为图标徽标（圆角方块底 + 图标），比纯色竖条更能区分分组；
 * 标题字号提到 bodyLarge 并加粗，扫读时更容易定位。
 */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(15.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            content()
        }
    }
}

/** 一行开关设置：左文右开关，纵向排布不变。 */
@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        AppSwitch(checked, onCheckedChange)
    }
}

/**
 * 统一样式的开关。
 *
 * M3 默认的 off 态轨道色是 surfaceVariant（本项目亮色下 0xFFEEF0F4），铺在白玻璃卡上
 * 几乎看不见，圆点也没有边界——用户反馈「未开启状态不好看」。这里：
 *  - off 轨道用更深的 outlineVariant，圆点给白色并加一点描边，两个状态下都有轮廓；
 *  - on 轨道用主题强调色（而非默认值），与 App 其他选中态一致。
 */
@Composable
private fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.surface,
            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

/**
 * v0.2.55：设置改为**整页**（以前是顶栏图标弹出的 AlertDialog）。
 *
 * 原因：① 设置项越加越多，对话框里那条窄滚动区又难滑又容易误触；
 * ② 对话框与「更多」页里的入口重复了一个设置入口，现在只留顶栏一个；
 * ③ 整页后能直接用页面级标题栏 + 返回，与其它页面一致。
 *
 * 内容本身一行未改（只是外面从对话框换成页面骨架），这些分区都是长期验证过的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenStorage: (() -> Unit)? = null,
    onOpenMcp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var confirmDeleteLocal by remember { mutableStateOf(false) }
    var confirmClearDrafts by remember { mutableStateOf(false) }
    var showDiagnosticLog by remember { mutableStateOf(false) }
    var diagnosticLog by remember { mutableStateOf("") }
    var pendingLogExport by remember { mutableStateOf("") }
    val logExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val success = uri != null && runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingLogExport) }
                ?: error("无法创建日志文件")
        }.isSuccess
        pendingLogExport = ""
        if (uri != null) viewModel.reportDiagnosticLogExport(success)
    }
    val saveFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setSaveFolder(uri)
    }
    LaunchedEffect(Unit) { viewModel.refreshLocalDraftCount() }
    // v0.2.55：设置是整页，返回键要能退回去。
    BackHandler(enabled = true) { onBack() }
    // v0.2.59：设置页是**独立整页**（v0.2.55 改的），它把内层 Scaffold 整个替换掉了，
    // 而 SnackbarHost 原来是挂在那个 Scaffold 上的——于是设置页里触发的提示
    // （如「测试连接」结果）找不到宿主，只能排队等退出设置页后才弹出来，
    // 看起来就像“点了没反应”。这里把同一个 snackbar 宿主再接一份。
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSystemInDarkTheme()) {
                        Color(0xFF14181E).copy(alpha = 0.62f)
                    } else {
                        Color.White.copy(alpha = 0.55f)
                    },
                ),
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    // 用项目已验证可用的 ChevronLeft；Outlined.ArrowBack 在本版本图标集里
                    // 未确认存在，不冒险引新图标。
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ChevronLeft, "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        SettingsContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
            onRequestDeleteLocal = { confirmDeleteLocal = true },
            onRequestClearDrafts = { confirmClearDrafts = true },
            onOpenStorage = onOpenStorage,
            onOpenMcp = onOpenMcp,
            onShowLog = {
                diagnosticLog = viewModel.diagnosticLog()
                showDiagnosticLog = true
            },
            onExportLog = {
                pendingLogExport = viewModel.diagnosticLog()
                logExportLauncher.launch("ComfyUIMobile-${System.currentTimeMillis()}.log")
            },
            onPickSaveFolder = { saveFolderLauncher.launch(null) },
        )
    }
    if (showDiagnosticLog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticLog = false },
            title = { Text("诊断日志") },
            text = {
                Text(
                    diagnosticLog,
                    modifier = Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showDiagnosticLog = false }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = {
                    pendingLogExport = diagnosticLog
                    logExportLauncher.launch("ComfyUIMobile-${System.currentTimeMillis()}.log")
                }) { Text("导出") }
            },
        )
    }
    if (confirmDeleteLocal) {
        ConfirmDialog(
            title = "删除全部本地作品",
            message = "只删除手机中现有的 ${state.localResults.size} 项本地作品，不会删除电脑端云端资产。白名单仍会生效，之后新生成的结果仍会保存到手机；已经保存的本地作品不会因云端以后删除而消失。",
            onDismiss = { confirmDeleteLocal = false },
            onConfirm = {
                viewModel.clearLocalCache()
                confirmDeleteLocal = false
            },
        )
    }
    if (confirmClearDrafts) {
        ConfirmDialog(
            title = "清除全部本地草稿",
            message = "将删除手机中所有工作流的本地未保存草稿（共 ${state.localDraftCount} 个），不影响服务器上的工作流文件。是否继续？",
            onDismiss = { confirmClearDrafts = false },
            onConfirm = {
                viewModel.clearAllWorkflowDrafts()
                confirmClearDrafts = false
            },
        )
    }
}

/**
 * 设置页的全部内容。抽成独立组件是为了让页面骨架（SettingsScreen）与内容解耦。
 */
@Composable
private fun SettingsContent(
    state: AppUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onRequestDeleteLocal: () -> Unit,
    onRequestClearDrafts: () -> Unit,
    onOpenStorage: (() -> Unit)?,
    onOpenMcp: (() -> Unit)? = null,
    onShowLog: () -> Unit,
    onExportLog: () -> Unit,
    onPickSaveFolder: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // —— 服务器 ——
        SettingsSection("服务器", icon = Icons.Outlined.Computer) {
            Text(
                state.activeServer?.baseUrl ?: "尚未连接",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.activeServer?.lastSeen?.takeIf { it > 0L }?.let {
                Text(
                    "最后在线：${formatTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.systemStats?.let { stats ->
                Text(
                    "ComfyUI ${stats.comfyVersion} · 前端 ${stats.frontendVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stats.devices.forEach {
                    Text(
                        "${it.name} · 显存 ${formatSize(it.vramFree)} / ${formatSize(it.vramTotal)} 可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.activeServer != null) {
                OutlinedButton(onClick = { viewModel.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("断开当前服务器")
                }
            }
        }

        // —— AI Studio ——
        SettingsSection("AI Studio", icon = Icons.Outlined.Bolt) {
            Text(
                "账号在「账号」页登录与管理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsToggleRow(
                title = "每日自动签到并领算力",
                subtitle = "打开 App 时自动完成，连续签到才不会断；平台的一次性任务（公开项目、发布模型等）需真实创作内容，不代做",
                checked = state.autoDailyTasks,
                onCheckedChange = viewModel::setAutoDailyTasks,
            )
        }

        // —— MCP 服务（v0.2.88）：入口在独立页 ——
        SettingsSection("MCP 服务", icon = Icons.Outlined.Extension) {
            Text(
                if (state.mcpServerEnabled) "运行中 · 127.0.0.1:${state.mcpServerPort}" else "关闭中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { onOpenMcp?.invoke() }, enabled = onOpenMcp != null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("进入 MCP 服务页")
            }
        }

        // —— 图片保存 ——
        SettingsSection("图片保存", icon = Icons.Outlined.Download) {
            Text(
                if (state.saveFolderUri != null) {
                    "已选择自定义目录，生成结果将保存到所选文件夹"
                } else {
                    "生成结果默认保存到系统相册 Pictures/ComfyUIMobile"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickSaveFolder,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("选择目录")
                }
                if (state.saveFolderUri != null) {
                    OutlinedButton(
                        onClick = { viewModel.setSaveFolder(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认")
                    }
                }
            }
            SettingsToggleRow(
                title = "生成完成自动保存到图片文件夹",
                subtitle = "任务完成后把最新结果自动写入上方选择的文件夹（未设置则只保留在本地作品）",
                checked = state.autoSaveResults,
                onCheckedChange = viewModel::setAutoSaveResults,
            )
        }

        // —— 本地作品白名单 ——
        SettingsSection("本地作品保存白名单", icon = Icons.Outlined.Favorite) {
            Text(
                "按输出部件类型对所有工作流生效，不绑定单个工作流。只保存本 App 提交的任务；电脑浏览器提交的任务不会进入本地。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.cacheOutputRules.isEmpty()) {
                Text(
                    "尚未添加输出部件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.cacheOutputRules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.nodeTitle.ifBlank { rule.nodeType }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${rule.nodeType} · 适用于所有工作流",
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                rule.serverUrl,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(rule.enabled) { viewModel.setCacheRuleEnabled(rule, it) }
                        IconButton(onClick = { viewModel.removeCacheRule(rule) }) {
                            Icon(Icons.Outlined.Delete, "删除白名单")
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onRequestDeleteLocal,
                enabled = state.localResults.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除全部本地作品（${state.localResults.size} 项）")
            }
        }

        // —— 本地草稿 ——
        SettingsSection("本地草稿", icon = Icons.Outlined.Save) {
            SettingsToggleRow(
                title = "保存本地草稿",
                subtitle = "关闭后打开工作流直接读取服务器版本，不再保存或恢复未保存修改",
                checked = state.localDraftsEnabled,
                onCheckedChange = viewModel::setLocalDraftsEnabled,
            )
            if (state.localDraftsEnabled) {
                Text(
                    "当前 ${state.localDraftCount} 个工作流有本地草稿",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onRequestClearDrafts,
                    enabled = state.localDraftCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清除全部本地草稿（${state.localDraftCount}）")
                }
            }
        }

        // —— 空间管理 ——
        SettingsSection("空间管理", icon = Icons.Outlined.Memory) {
            Text(
                "查看 App 的内存与磁盘占用明细，并逐项清理本地作品、缓存、日志",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { onOpenStorage?.invoke() }, enabled = onOpenStorage != null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Memory, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("打开空间管理")
            }
        }

        // —— 诊断日志 ——
        SettingsSection("诊断日志", icon = Icons.Outlined.FileOpen) {
            SettingsToggleRow(
                title = "记录运行和闪退日志",
                subtitle = "不记录提示词、工作流正文或生成图片",
                checked = state.loggingEnabled,
                onCheckedChange = viewModel::setLoggingEnabled,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onShowLog) { Text("查看日志") }
                OutlinedButton(onClick = onExportLog) { Text("导出日志") }
                TextButton(onClick = viewModel::clearDiagnosticLog) { Text("清空日志") }
            }
        }

        // —— 软件更新 ——
        SettingsSection("软件更新", icon = Icons.Outlined.Refresh) {
            OutlinedButton(onClick = { viewModel.checkUpdate() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("检查更新")
            }
            state.updateInfo?.let { info ->
                Text("发现 ${info.tag}", style = MaterialTheme.typography.bodyMedium)
                if (state.updateDownloading) {
                    LinearProgressIndicator(
                        progress = { state.updateDownloadProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "下载中 ${((state.updateDownloadProgress ?: 0f) * 100).toInt()}% · ${state.updateDownloadSource.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = viewModel::downloadUpdate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("下载并安装")
                    }
                }
            }
        }
    }
}


@Composable
private fun EmptyState(icon: ImageVector, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SaveWorkflowAsDialog(
    initialName: String,
    initialFolder: String,
    folders: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var folder by remember(initialFolder) { mutableStateOf(initialFolder) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工作流另存为") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    singleLine = true,
                )
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { folderMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Folder, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (folder == "workflows") "工作流根目录" else folder.removePrefix("workflows/"),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(
                        expanded = folderMenuExpanded,
                        onDismissRequest = { folderMenuExpanded = false },
                    ) {
                        folders.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (option == "workflows") "工作流根目录" else option.removePrefix("workflows/"))
                                },
                                leadingIcon = {
                                    Icon(
                                        if (option == folder) Icons.Outlined.CheckCircle else Icons.Outlined.Folder,
                                        null,
                                    )
                                },
                                onClick = {
                                    folder = option
                                    folderMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Text("保存位置：$folder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, folder) }, enabled = name.isNotBlank()) { Text("另存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WorkflowDraftConflictDialog(
    message: String,
    onKeepLocal: () -> Unit,
    onLoadServer: () -> Unit,
    onSaveAs: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepLocal,
        title = { Text("本地草稿与服务器版本冲突") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onKeepLocal) { Text("继续手机草稿") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onLoadServer) { Text("读取服务器版") }
                TextButton(onClick = onSaveAs) { Text("另存") }
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "确认",
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun displayName(context: Context, uri: Uri): String? = context.contentResolver
    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun formatSize(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
    value >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
    else -> "$value B"
}

private fun formatTime(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

private fun formatDuration(value: Long): String {
    val totalSeconds = value / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分${seconds}秒"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 去掉无意义的小数尾巴，并保留至多 1 位小数：32.0 → 32，62.6833 → 62.7。 */
private fun trimNumber(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/** 配额类型（平台 key）→ 人话。 */
private fun quotaTypeLabel(type: String): String = when (type.uppercase()) {
    "DEV" -> "基础版（CPU）"
    "DCU" -> "异构算力 DCU"
    "V100" -> "高级版 V100"
    "A100" -> "至尊版 A100"
    else -> type
}

private fun previewUrl(media: ResultMedia): String =
    if (media.kind == MediaKind.IMAGE && media.source == ResultSource.CLOUD) {
        // v0.2.51：以前写死用 `&` 追加，URL 本身没查询参数时会拼出 `.../view&preview=...`
        // 这种非法地址（参数丢失、缩略图拿不到）。改由 UrlQuery 按是否已有 query 选择。
        UrlQuery.append(media.url, "preview", "webp;90")
    } else {
        media.url
    }

// ===================== v0.1.90：账号页 =====================

/**
 * 账号页——App 的新首页。
 *
 * v0.1.91：不再自带 Scaffold/TopAppBar——外层 ConnectedApp 已统一提供标题栏，
 * 页内只负责内容。之前每页套一层 Scaffold，截图里出现了双层标题栏。
 * 布局按「我是谁 → 我有什么 → 我能做什么」排：身份条、积分与算力、项目、
 * 服务器连接。
 */
@Composable
private fun AccountScreen(state: AppUiState, viewModel: MainViewModel) {
    val panel = state.aiStudio
    val context = LocalContext.current
    var showPointsInfo by remember { mutableStateOf(false) }
    var showComputeInfo by remember { mutableStateOf(false) }
    var pendingDeleteAccount by remember { mutableStateOf<AiStudioAccount?>(null) }
    // 打开登录页时是否要“添加新账号”（已登录其它账号时需要先清 WebView 登录态）。
    var addAccountMode by remember { mutableStateOf(false) }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        addAccountMode = false
        if (result.resultCode == Activity.RESULT_OK) viewModel.onAiStudioLoggedIn()
    }
    LaunchedEffect(panel.activeAccountId) {
        if (panel.activeAccount() != null) {
            viewModel.aiStudioRefreshAccount()
            // 以前只刷资源、不拉项目列表，于是冷启动进账号页项目永远是空的，
            // 要手动点「刷新」（用户反馈）。这里一并拉取，silent 避免闪圈。
            if (panel.projects.isEmpty()) viewModel.aiStudioLoadProjects(silent = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
            // —— 身份条 ——
            item {
                AccountIdentityCard(
                    panel = panel,
                    onLogin = {
                        addAccountMode = false
                        loginLauncher.launch(Intent(context, AiStudioLoginActivity::class.java))
                    },
                    onAddAccount = {
                        addAccountMode = true
                        loginLauncher.launch(AiStudioLoginActivity.intent(context, forceLogout = true))
                    },
                    onSelectAccount = { accountId -> viewModel.selectAiStudioAccount(accountId) },
                    onRemoveAccount = { accountId ->
                        pendingDeleteAccount = panel.accounts.firstOrNull { it.id == accountId }
                    },
                )
            }

            // —— 积分与算力 ——
            if (panel.activeAccount() != null) {
                item {
                    Text("账号资源", style = MaterialTheme.typography.titleMedium)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ResourceTile(
                            modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Bolt, null, Modifier.size(20.dp)) },
                            label = "积分",
                            value = panel.points?.toString() ?: "—",
                            hint = if (panel.points == null) "未读到" else null,
                            onClick = { showPointsInfo = true },
                        )
                        ResourceTile(
                            modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Memory, null, Modifier.size(20.dp)) },
                            label = "算力卡",
                            // 与官网完全一致：官网用户卡就是 resourceTotal ÷ 60 保留 1 位小数，
                            // 这里只做同一道除法，不额外换算、不加单位。
                            value = panel.computeCard ?: "—",
                            hint = if (panel.computeCard == null) "未读到" else null,
                            onClick = { showComputeInfo = true },
                        )
                        ResourceTile(
                            modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Payments, null, Modifier.size(20.dp)) },
                            label = "A币",
                            value = panel.aCoin ?: "—",
                            hint = if (panel.aCoin == null) "未读到" else null,
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.aiStudioSignIn() },
                            modifier = Modifier.weight(1f),
                            enabled = !panel.signingIn,
                        ) {
                            if (panel.signingIn) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (panel.signedInToday) "今日已签到" else "签到")
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.aiStudioReceiveResource() },
                            modifier = Modifier.weight(1f),
                            // v0.2.58：忙时禁用。以前没有 enabled 保护，任务在跑时点击会静默
                            // 无反应（ViewModel 里 isActive 就 return，界面不给任何反馈）。
                            enabled = !panel.loadingProjects && !panel.consoleBusy,
                        ) { Text("领算力") }
                    }
                    // v0.2.57：这个按钮容易被误解为“点一下就到账 8 点”，但实测
                    // （2026-09-30，两个真实账号）确认：每日 8 点算力是「启动项目」时
                    // 平台自动发的（启动前后 49.0 → 57.0，+480 分钟；同日第二次不再发）。
                    // 本按钮只在平台有可领额度时才生效；没额度时无论项目是否在跑都回
                    // “无效操作”。把规则写在按钮下面，避免用户反复点一个不会生效的按钮。
                    Text(
                        "每日 8 点算力由平台在「启动项目」时自动发放（免费 CPU 档也会发）；" +
                            "这个按钮只在活动/补发等有余量时才生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // —— 项目 ——
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("我的项目", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (panel.loadingProjects) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { viewModel.aiStudioLoadProjects() }) { Text("刷新") }
                        }
                    }
                }
                if (panel.projects.isEmpty() && !panel.loadingProjects) {
                    item { Text("没有读到项目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(panel.projects, key = { it.projectId }) { project ->
                    ProjectCard(project = project, panel = panel, viewModel = viewModel)
                }
            }

            // —— 本周各档剩余（平台：高级GPU环境使用时间本周剩余）——
            if (panel.weekQuota.isNotEmpty()) {
                item {
                    Text("本周算力剩余", style = MaterialTheme.typography.titleMedium)
                }
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            panel.weekQuota.forEach { (type, minutes) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        quotaTypeLabel(type),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${trimNumber(minutes / 60.0)} 小时",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Text(
                                "同一份算力卡换成不同显卡，能跑的小时数不一样",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // —— ComfyUI 连接（可展开卡片）——
            item {
                Text("ComfyUI 服务器", style = MaterialTheme.typography.titleMedium)
            }
            item {
                ServerConnectionCard(state = state, viewModel = viewModel)
            }

            panel.error?.let { error ->
                item {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            panel.message?.let { message ->
                item {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            // 平台接口会改版：解析抓不到字段时，用户能把真实响应复制出来反馈。
            panel.lastRawResponse?.takeIf { it.isNotBlank() }?.let { raw ->
                item { RawResponseCard(raw = raw, context = context) }
            }
        }

    pendingDeleteAccount?.let { target ->
        ConfirmDialog(
            title = "删除账号",
            message = "将从本机移除「${target.displayName()}」的登录信息。不会影响平台上的账号本身，之后可重新登录。",
            confirmLabel = "删除",
            onDismiss = { pendingDeleteAccount = null },
        ) {
            viewModel.removeAiStudioAccount(target.id)
            pendingDeleteAccount = null
        }
    }

    if (showComputeInfo) {
        AlertDialog(
            onDismissRequest = { showComputeInfo = false },
            title = { Text("算力卡") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("卡片上的数字与 AI Studio 官网显示的一致。")
                    Text("它表示你本周还能用的 GPU 运行时长，按基础版（CPU）环境折算。换 V100、A100 等显卡时消耗更快，实际能跑的小时数会更少。")
                    panel.weekQuota.entries.firstOrNull()?.let {
                        Text("本周各档剩余见下方「本周算力剩余」。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showComputeInfo = false }) { Text("知道了") }
            },
        )
    }

    if (showPointsInfo) {
        AlertDialog(
            onDismissRequest = { showPointsInfo = false },
            title = { Text("积分") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("积分来自平台上的日常任务（签到、发布项目等），可以换取算力卡、实物奖品等。")
                    Text("当前积分：${panel.points ?: "未读到"}")
                    Text(
                        "积分用途与兑换入口在 AI Studio 官网的积分页（/pointsoverview）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPointsInfo = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun AccountIdentityCard(
    panel: AiStudioState,
    onLogin: () -> Unit,
    onAddAccount: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
) {
    val account = panel.activeAccount()
    var expanded by remember { mutableStateOf(false) }
    // 玻璃卡：这是账号页最顶部的身份条，放在极光渐变上最能体现"液态玻璃"。
    GlassCard(modifier = Modifier.fillMaxWidth(), strong = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (account == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("未登录 AI Studio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "登录后可以签到、看项目、选算力启动云端 ComfyUI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录百度 AI Studio") }
            } else {
                // 身份行：点整行展开/收起账号面板。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                ) {
                    Icon(Icons.Outlined.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.displayName(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (account.uid.isNotBlank()) "UID ${account.uid}" else "UID 未知",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (panel.accounts.size > 1) {
                        Text("${panel.accounts.size} 个账号", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (expanded) "收起账号列表" else "展开账号列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        panel.accounts.forEach { item ->
                            AccountRow(
                                account = item,
                                selected = item.id == panel.activeAccountId,
                                onClick = { onSelectAccount(item.id) },
                                onRemove = { onRemoveAccount(item.id) },
                            )
                        }
                    }
                    OutlinedButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加账号")
                    }
                }
            }
        }
    }
}

/** 账号面板里的一行：昵称 + UID + 最后使用 + 今日签到标记，行尾可删除。 */
@Composable
private fun AccountRow(
    account: AiStudioAccount,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Icon(
            if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
            null,
            Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(account.displayName(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildList {
                    if (account.uid.isNotBlank()) add("UID ${account.uid}")
                    if (account.lastUsedAt > 0L) add("最后使用 ${formatTime(account.lastUsedAt)}")
                    if (account.lastSignInAt > 0L) add("最近签到 ${formatTime(account.lastSignInAt)}")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, "删除账号", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 一块数据瓦片。
 *
 * 拿不到值时显示「—」而不是编一个数字——平台接口改版是常态，宁可留白
 * 也不能用假数据骗人。
 *
 * 图标放进圆形淡底里（而不是裸图标）：三块瓦片并排时，带底的图标在视觉上
 * 更成组、数字也更容易扫到；裸图标会让三列显得散。
 */
@Composable
private fun ResourceTile(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    hint: String?,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    GlassCard(modifier = cardModifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer,
                        content = icon,
                    )
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ProjectCard(project: AiStudioProject, panel: AiStudioState, viewModel: MainViewModel) {
    val starting = panel.startingProjectId == project.projectId
    val stopping = panel.stoppingProjectId == project.projectId
    // 环境已受理但地址还没拿到——卡片显示“正在启动环境…”。此时停止会把刚分配
    // 好的机器直接回收，用户会以为“启动失败”（cf07 日志：提交后 33 秒点停止，
    // 环境 43 秒才真正就绪，一次都没用上）。所以这时先弹确认。
    val allocating = project.running && panel.environmentReadyProjectId != project.projectId
    var expanded by remember(project.projectId) { mutableStateOf(false) }
    var confirmStop by remember(project.projectId) { mutableStateOf(false) }
    // v0.1.98：整张卡片不再可点。以前 onClick 挂在 OutlinedCard 上，会与
    // 内部按钮抢事件（点「选择 GPU 启动」反而触发卡片展开/收起），这就是
    // “选了也没反应”的根因之一。现在只有按钮可点。
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(project.displayName(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            starting -> "正在启动…"
                            stopping -> "正在停止…"
                            // 平台的 running 只是受理回执，环境地址未确认前不报"运行中"——
                            // 否则用户看到「运行中」就点连接，撞上"平台没有返回环境地址"。
                            project.running && panel.environmentReadyProjectId == project.projectId -> "运行中"
                            project.running -> "正在启动环境…"
                            else -> "已停止"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (starting || stopping || project.running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 项目已受理但环境还在分配时，给个转圈——比干等一个静态文案好。
                if (starting || stopping || (project.running && panel.environmentReadyProjectId != project.projectId)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (expanded) {
                HorizontalDivider()
                if (!panel.schedulesLoaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取可用 GPU…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (panel.schedules.isEmpty()) {
                    Text(
                        "没有读到可用档位，可直接用「默认档启动」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v0.2.92：把失败原因摆出来。之前的兜底只说了"没读到"，
                    // 用户不知道是网络、登录过期还是被平台风控拦了——而不同原因
                    // 要做的事完全不同（重试 / 重登 / 去浏览器过人机验证）。
                    panel.error?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { viewModel.aiStudioLoadSchedules(project.projectId) }) {
                            Text("重试读取档位", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    panel.schedules.forEach { schedule ->
                        OutlinedButton(
                            onClick = {
                                viewModel.aiStudioStartProject(project.projectId, schedule.scheduleName)
                                expanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !starting && !stopping && schedule.available,
                        ) {
                            val cost = schedule.costPerHour
                                ?.takeIf { it > 0 }
                                ?.let { " · ${trimNumber(it / 100.0)} 算力卡/小时" }
                                .orEmpty()
                            Text(schedule.displayName() + cost)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // v0.2.41：运行中的项目不允许再点启动（正在用的机器没回收就再提交
                // 启动会白扣算力，平台也可能拒绝），按钮直接置灰，并把正在使用的
                // GPU 显示在按钮上（"使用中 · V100 16GB"）；读不到型号时退回"使用中"。
                val gpuInUse = project.running
                Button(
                    onClick = {
                        if (expanded) {
                            // 已展开则用默认档启动，避免再点一次无反应
                            viewModel.aiStudioStartProject(project.projectId, "")
                        } else {
                            viewModel.aiStudioLoadSchedules(project.projectId)
                            expanded = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !starting && !stopping && !gpuInUse,
                ) {
                    Text(
                        when {
                            gpuInUse -> project.runningGpuLabel
                                .takeIf { it.isNotBlank() }
                                ?.let { "使用中 · $it" } ?: "使用中"
                            expanded -> "默认档启动"
                            else -> "选择 GPU 启动"
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (allocating) confirmStop = true
                        else viewModel.aiStudioStopProject(project.projectId)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !starting && !stopping && project.running,
                ) { Text("停止") }
            }
            if (confirmStop) {
                ConfirmDialog(
                    title = "停止项目",
                    message = "环境还在分配中。现在停止会中断本次启动，已分配的机器会被平台回收。确定要停止吗？",
                    onDismiss = { confirmStop = false },
                ) {
                    confirmStop = false
                    viewModel.aiStudioStopProject(project.projectId)
                }
            }
        }
    }
}

/**
 * ComfyUI 连接卡片——**默认收起**，点开才出输入框。
 *
 * 以前这个表单是整页的，没填地址就进不了 App。现在它只是账号页里的一张卡片：
 * 收起时只占一行，显示当前服务器与状态（未连接时文案会提示点开填地址），
 * 不抢账号功能的视觉重心。
 */
@Composable
private fun ServerConnectionCard(state: AppUiState, viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val server = state.activeServer
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Link, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        server?.name ?: "未连接",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            server == null -> "点这里填地址，连接局域网或云端 ComfyUI"
                            state.status == ConnectionStatus.CONNECTED -> "在线 · 队列 ${state.queueRemaining}"
                            state.status == ConnectionStatus.CONNECTING -> "正在连接…"
                            state.status == ConnectionStatus.RECONNECTING -> "正在重连"
                            state.status == ConnectionStatus.ERROR -> "连接出错"
                            else -> state.connectionMessage
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起" else "展开",
                )
            }
            if (expanded) {
                HorizontalDivider()
                OutlinedTextField(
                    value = LanAddress.withoutCredentials(state.serverInput),
                    onValueChange = viewModel::setServerInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ComfyUI 地址") },
                    placeholder = { Text("http://192.168.1.10:8188") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.serverCookie,
                    onValueChange = viewModel::setServerCookie,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("认证 Cookie（可选）") },
                    minLines = 2,
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.connect() },
                        modifier = Modifier.weight(1f),
                        enabled = !state.loading,
                    ) { Text("连接") }
                    OutlinedButton(
                        onClick = viewModel::scanLan,
                        modifier = Modifier.weight(1f),
                        enabled = !state.scanning,
                    ) { Text("扫描局域网") }
                }
                if (server != null) {
                    OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) {
                        Text("断开当前服务器")
                    }
                }
                if (state.savedServers.isNotEmpty()) {
                    Text("已保存", style = MaterialTheme.typography.labelMedium)
                    state.savedServers.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setServerInput(profile.baseUrl)
                                    viewModel.setServerCookie(profile.cookie)
                                    viewModel.connect(profile.baseUrl)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(profile.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawResponseCard(raw: String, context: Context) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "原始响应（解析异常时反馈用）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AI Studio 响应", raw))
                }) { Icon(Icons.Outlined.ContentCopy, "复制原始响应") }
            }
            Text(
                raw.take(500),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ===================== 控制台页 =====================

/** 一个终端特殊键：显示文字 + 实际发给 PTY 的字节序列。 */
private data class TerminalKey(val label: String, val sequence: String)

/**
 * 特殊键行内容。
 *
 * 挑选依据：这些是软键盘实际打不出、而 shell 里天天要用的控制字符。
 * 参考 Termius / MuxPod / Mobile SSH 的「extra key row」都是同一路思路。
 * 注意顺序：把 ESC 与 Ctrl 类放前面（vim、中断场景最常用）。
 */
private val TERMINAL_KEYS = listOf(
    TerminalKey("ESC", "\u001B"),
    TerminalKey("TAB", "\t"),
    TerminalKey("Ctrl+C", "\u0003"),
    TerminalKey("Ctrl+D", "\u0004"),
    TerminalKey("Ctrl+L", "\u000C"),
    TerminalKey("Ctrl+Z", "\u001A"),
    TerminalKey("↑", "\u001B[A"),
    TerminalKey("↓", "\u001B[B"),
    TerminalKey("←", "\u001B[D"),
    TerminalKey("→", "\u001B[C"),
    TerminalKey("Home", "\u001B[H"),
    TerminalKey("End", "\u001B[F"),
    TerminalKey("PgUp", "\u001B[5~"),
    TerminalKey("PgDn", "\u001B[6~"),
    TerminalKey("⌫", "\u007F"),
    TerminalKey("|", "|"),
    TerminalKey("~", "~"),
    TerminalKey("-", "-"),
)

/** 特殊键行 / 字号调节里的小按钮（等宽、深底浅字，与终端同调）。 */
@Composable
private fun TerminalKeyButton(
    label: String,
    enabled: Boolean,
    theme: TerminalTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(theme.inputBg.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (enabled) theme.text else theme.text.copy(alpha = 0.35f),
        )
    }
}

/**
 * 顶栏里的 ComfyUI 服务入口：一个圆形电脑图标（在「设置」左边），点击在图标**下方展开**
 * 一个小面板，里面是状态 + 连接/断开按钮；再点图标收回。
 *
 * 用 DropdownMenu 而不是 AnimatedVisibility：它是系统的弹出层，不会被 TopAppBar 裁剪，
 * 自带「点外部关闭」「跟随锚点定位」这些行为，正是"向下展开"要的效果。
 *
 * 为什么它值得存在：自动连接有可能失败（WS 被反代抬断、探测时机太早等）。以前
 * 底部有一整张卡片守着这个备用入口，现在收成图标，不再占页面面积。
 */
@Composable
private fun ComfyServiceChip(
    state: AppUiState,
    viewModel: MainViewModel,
    onSwitchServer: () -> Unit,
) {
    val panel = state.aiStudio
    val comfyUrl = panel.comfyUiUrl
    val comfyConnected = comfyUrl != null &&
        state.status == ConnectionStatus.CONNECTED &&
        state.activeServer?.baseUrl?.let { LanAddress.sameServer(it, comfyUrl) } == true
    val connecting = state.status == ConnectionStatus.CONNECTING && state.activeServer == null
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = comfyUrl != null && !connecting) { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            comfyConnected -> MaterialTheme.colorScheme.primaryContainer
                            connecting -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.Computer,
                        if (comfyConnected) "ComfyUI 已连接" else "ComfyUI 服务",
                        Modifier.size(17.dp),
                        tint = when {
                            comfyConnected -> MaterialTheme.colorScheme.primary
                            comfyUrl != null -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            if (comfyConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                    )
                    Text(
                        if (comfyConnected) "ComfyUI 已连接" else "ComfyUI 未连接",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    comfyUrl ?: "终端连上后才会得到服务地址",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Spacer(Modifier.height(6.dp))
                if (comfyConnected) {
                    // 接管原顶栏「刷新」按钮的职责：轻量检查服务器连接。
                    TextButton(
                        onClick = {
                            viewModel.refreshOrReconnect()
                            expanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("刷新连接") }
                    OutlinedButton(
                        onClick = {
                            viewModel.disconnect()
                            expanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("断开") }
                } else {
                    Button(
                        onClick = {
                            // 走 aiStudioRefreshComfyUi 而不是直接 connect：它会先重置
                            // autoConnectAttempted 再探测。这才是真正的「重试」——自动连接
                            // 失败后光调 connect 不会让后续自动连接再有机会。
                            viewModel.aiStudioRefreshComfyUi()
                            expanded = false
                        },
                        enabled = comfyUrl != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("连接") }
                }
                // 接管原顶栏「切换服务器」按钮的职责：回到账号页选另一台。
                TextButton(
                    onClick = {
                        viewModel.disconnect()
                        onSwitchServer()
                        expanded = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("切换服务器") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsoleScreen(state: AppUiState, viewModel: MainViewModel) {
    val panel = state.aiStudio
    val terminalState = rememberLazyListState()
    val context = LocalContext.current
    // 终端配色主题：用户可选，存偏好。默认第一套。
    val theme = remember(panel.consoleThemeId) { resolveTerminalTheme(panel.consoleThemeId) }
    var themePickerExpanded by remember { mutableStateOf(false) }
    // 常用命令面板的展开/添加态：纯 UI 瞬时状态，不必进 ViewModel。
    var commandsExpanded by remember { mutableStateOf(false) }
    var addingCommand by remember { mutableStateOf(false) }
    var newCommandDraft by remember { mutableStateOf("") }
    // 编辑态：只有进入编辑态才显示删除按钮。以前每个药丸右侧常驻一个小「×」，
    // 一点就删、还直接写盘，手指划过就误删（用户反馈“常用命令内容不见了”）。
    var editingCommands by remember { mutableStateOf(false) }
    // 最近删除的一条：删除是立即写盘的，没有它就找不回来。留一份以便“撤销”。
    var lastRemovedCommand by remember { mutableStateOf<String?>(null) }
    // 特殊键行（ESC/TAB/方向键/Ctrl 组合…）的展开态。
    //
    // 为什么默认收起：移动端终端必须能发 ESC/TAB/方向键（软键盘打不出来），
    // 但它同时会占掉一屏高度、把输入框挤小。做成可展开的抽屉，两者兼得。
    var keysExpanded by remember { mutableStateOf(false) }
    // 终端字号：12.5sp 偏小，给用户可调。
    var fontSizeSp by remember { mutableStateOf(12.5f) }
    // 终端内容导出：先缓存要写出的文本，再启动系统「新建文件」选择器。
    //
    // 为什么用导出而不是复制到剪贴板：手机剪贴板对超长文本会截断，终端输出
    // 动辄几万行，复制出去只剩一小段；导出成文件才能完整拿到。
    var pendingTerminalExport by remember { mutableStateOf("") }
    val terminalExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingTerminalExport.toByteArray()) }
            }.isSuccess
            Toast.makeText(context, if (ok) "已导出终端内容" else "导出失败", Toast.LENGTH_SHORT).show()
        }
        pendingTerminalExport = ""
    }

    // 新输出到了就滚到底，不然用户看不到刚跑出来的日志。
    LaunchedEffect(panel.terminalLines.size) {
        if (panel.terminalLines.isNotEmpty()) {
            terminalState.animateScrollToItem(panel.terminalLines.lastIndex)
        }
    }

    // ComfyUI 服务入口已上移到标题栏（见 ComfyServiceChip），这里不再需要。

    Column(Modifier.fillMaxSize()) {
        // ========== 终端（铺满顶栏与底栏之间的整个中间区域）==========
        // 不再用玻璃卡包起来：用户希望中间区域全交给终端。标题栏（状态+操作）
        // 与输入行直接贴在深色终端底上，整页就是一块终端。
        //
        // 标题栏：状态点 + 状态 + 操作。
        // ⚠️ 「连接/断开」必须常驻：以前重做时只留了中断/清屏/复制三个图标，
        // 把连接入口弄丢了（用户截图里找不到按钮）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.bg)
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(
                    when {
                        panel.consoleBusy -> MaterialTheme.colorScheme.tertiary
                        panel.consoleConnected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                ),
            )
            Column(Modifier.weight(1f)) {
                Text("终端", style = MaterialTheme.typography.titleSmall, color = theme.text)
                Text(
                    when {
                        panel.consoleBusy -> "正在连接…"
                        panel.consoleConnected -> "已连接 · 项目环境"
                        else -> "未连接"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.text.copy(alpha = 0.6f),
                )
            }
            if (panel.consoleBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            // 已连接时的次要操作：中断（Ctrl+C）/ 清屏 / 复制。
            if (panel.consoleConnected) {
                IconButton(onClick = { viewModel.aiStudioInterruptConsole() }) {
                    Icon(Icons.Outlined.Warning, "中断当前命令", Modifier.size(20.dp), tint = theme.text)
                }
            }
            IconButton(
                onClick = { viewModel.aiStudioClearConsole() },
                enabled = panel.terminalLines.isNotEmpty(),
            ) {
                Icon(
                    Icons.Outlined.Delete, "清屏", Modifier.size(20.dp),
                    tint = if (panel.terminalLines.isNotEmpty()) theme.text
                    else theme.text.copy(alpha = 0.35f),
                )
            }
            IconButton(
                onClick = {
                    // 导出为纯文本：把 ANSI 转义码剥掉，落盘的是人能读的内容。
                    pendingTerminalExport = panel.terminalLines.joinToString("\n") { AiStudioProtocol.stripAnsi(it) }
                    terminalExportLauncher.launch("ComfyUIMobile-terminal-${System.currentTimeMillis()}.txt")
                },
                enabled = panel.terminalLines.isNotEmpty(),
            ) {
                Icon(
                    Icons.Outlined.Save, "导出终端内容", Modifier.size(20.dp),
                    tint = if (panel.terminalLines.isNotEmpty()) theme.text
                    else theme.text.copy(alpha = 0.35f),
                )
            }
            // 主操作：连接 / 断开（一直显示，不可缺）。
            if (panel.consoleConnected) {
                OutlinedButton(
                    onClick = { viewModel.aiStudioDisconnectConsole() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("断开") }
            } else {
                Button(
                    onClick = { viewModel.aiStudioConnectConsole() },
                    enabled = !panel.consoleBusy,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) { Text("连接") }
            }
        }

        HorizontalDivider(color = theme.text.copy(alpha = 0.12f))

        // —— 输出区（深底浅字，等宽；支持 ANSI 着色）——
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(theme.bg),
        ) {
            // 把实测列数报给远端 PTY（按等宽字估算），否则它按默认 80 列
            // 排版，窄屏上长行硬折。
            val density = LocalDensity.current
            val cols = remember(maxWidth, density.fontScale, fontSizeSp) {
                with(density) {
                    val charWidthPx = fontSizeSp.sp.toPx() * 0.6f
                    (maxWidth.toPx() / charWidthPx).toInt().coerceIn(20, 200)
                }
            }
            LaunchedEffect(cols, panel.consoleConnected) {
                if (panel.consoleConnected) viewModel.aiStudioResizeConsole(cols)
            }
            if (panel.terminalLines.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        null,
                        Modifier.size(30.dp),
                        tint = theme.text.copy(alpha = 0.45f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (panel.consoleConnected) "终端已就绪，在下方输入命令" else "点右上角「连接」启动终端",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.text.copy(alpha = 0.65f),
                    )
                }
            } else {
                LazyColumn(
                    state = terminalState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    // v0.2.53：给行加 key。终端是流式追加的，没 key 时每次新输出到达都
                    // 会把可见行当成新项重组，连带重跑下面的 ANSI 解析；有了 key，
                    // Compose 能复用已存在的行。
                    itemsIndexed(panel.terminalLines, key = { index, _ -> index }) { _, line ->
                        // 逐行包 SelectionContainer：支持长按选择复制，又不干扰
                        // LazyColumn 自身的滚动。
                        SelectionContainer {
                            // ANSI 解析是正则逐行扫，终端滚动时每帧都会重组；
                            // 记住上一行结果，避免反复重跑（长会话下这很可观）。
                            val annotated = remember(line, theme) { terminalAnnotatedLine(line.ifEmpty { " " }, theme) }
                            Text(
                                annotated,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSizeSp.sp,
                                    lineHeight = (fontSizeSp * 1.6f).sp,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // —— 内嵌输入行 ——
        // 底色故意比输出区**略亮**（theme.inputBg vs theme.bg）：
        // 以前输入行与输出区同色，发送按钮又是深色，三者糊成一片，用户
        // 看不出哪里能打字、按钮在哪。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.inputBg)
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "$",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = theme.prompt,
            )
            BasicTextField(
                value = panel.consoleDraft,
                onValueChange = viewModel::aiStudioUpdateConsoleDraft,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = panel.consoleConnected,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = theme.text,
                ),
                cursorBrush = SolidColor(theme.prompt),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    viewModel.aiStudioSendCommand(panel.consoleDraft.trim())
                }),
                decorationBox = { inner ->
                    if (panel.consoleDraft.isEmpty()) {
                        Text(
                            if (panel.consoleConnected) "输入命令" else "先连接终端",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = theme.text.copy(alpha = 0.4f),
                        )
                    }
                    inner()
                },
            )
            // 「特殊键」入口：软键盘打不出 ESC/TAB/方向键，需要它们时点开。
            IconButton(
                onClick = { keysExpanded = !keysExpanded },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    "特殊键",
                    Modifier.size(19.dp),
                    tint = if (keysExpanded) theme.prompt else theme.text.copy(alpha = 0.85f),
                )
            }
            // 「常用命令」入口：点开是个可增删的命令面板。
            IconButton(
                onClick = { commandsExpanded = !commandsExpanded },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    if (commandsExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    "常用命令",
                    Modifier.size(20.dp),
                    tint = theme.text.copy(alpha = 0.85f),
                )
            }
            // 发送键：青绿实心 + 白箭头，在深底上一眼可见。
            FilledIconButton(
                onClick = { viewModel.aiStudioSendCommand(panel.consoleDraft.trim()) },
                enabled = panel.consoleConnected && panel.consoleDraft.isNotBlank(),
                modifier = Modifier.size(34.dp),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "发送", Modifier.size(18.dp)) }
        }

        // —— 特殊键行（可展开）——
        // 移动端终端的刚需：软键盘发不出 ESC/TAB/方向键/Ctrl 组合，但 shell 里到处要用
        // （vim 退出、命令补全、翻历史、中断）。做成抽屉式，默认收起不挤压输入框。
        if (keysExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(theme.inputBg)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TERMINAL_KEYS.forEach { key ->
                        TerminalKeyButton(key.label, enabled = panel.consoleConnected, theme = theme) {
                            viewModel.aiStudioSendKey(key.sequence)
                        }
                    }
                }
                // 字号调节：参考里移动终端全都支持（捏合缩放），这里用 −/＋ 更精准。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "字号",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.text.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.weight(1f))
                    TerminalKeyButton("A−", enabled = fontSizeSp > 9f, theme = theme) { fontSizeSp -= 1f }
                    Text(
                        "${fontSizeSp.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.text,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    TerminalKeyButton("A+", enabled = fontSizeSp < 22f, theme = theme) { fontSizeSp += 1f }
                }
                // 配色主题：展开后横向排列，选中项高亮。与参考 App 的「配色主题」一致。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "配色主题",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.text.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { themePickerExpanded = !themePickerExpanded }) {
                        Text(theme.label)
                    }
                }
                if (themePickerExpanded) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TERMINAL_THEMES.forEach { item ->
                            val selected = item.id == theme.id
                            OutlinedButton(
                                onClick = { viewModel.aiStudioSetConsoleTheme(item.id) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) theme.prompt.copy(alpha = 0.25f) else Color.Transparent,
                                    contentColor = if (selected) theme.prompt else theme.text,
                                ),
                            ) { Text(item.label) }
                        }
                    }
                }
            }
        }

        // —— 常用命令面板（展开后可点、可删、可加）——
        if (commandsExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(theme.inputBg)
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (editingCommands) "常用命令 · 点击即删除" else "常用命令",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.text.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    // 编辑/添加互斥：两个操作都要占用药丸的点击，不能同时生效。
                    TextButton(onClick = {
                        editingCommands = !editingCommands
                        if (editingCommands) addingCommand = false
                    }) {
                        Text(if (editingCommands) "完成" else "编辑")
                    }
                    TextButton(onClick = {
                        addingCommand = !addingCommand
                        if (addingCommand) editingCommands = false
                    }) {
                        Text(if (addingCommand) "收起" else "添加")
                    }
                }
                if (addingCommand) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newCommandDraft,
                            onValueChange = { newCommandDraft = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("如 comfyui --cpu", style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Button(
                            onClick = {
                                viewModel.aiStudioAddConsoleQuickCommand(newCommandDraft)
                                newCommandDraft = ""
                            },
                            enabled = newCommandDraft.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text("添加") }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    panel.consoleQuickCommands.forEach { item ->
                        if (editingCommands) {
                            // 编辑态：点击即删除（进入编辑态本身已是一次刻意操作，
                            // 不会像以前那样被随手一滑误触）。删后给一次撤销机会。
                            InputChip(
                                selected = false,
                                onClick = {
                                    lastRemovedCommand = item
                                    viewModel.aiStudioRemoveConsoleQuickCommand(item)
                                },
                                label = { Text(item, style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Outlined.Close,
                                        "删除",
                                        Modifier.size(16.dp),
                                        tint = Color(0xFFF07178),
                                    )
                                },
                            )
                        } else {
                            // 平时：药丸只有命令文字，点击=填入终端，没有任何删除入口。
                            InputChip(
                                selected = false,
                                onClick = { viewModel.aiStudioSetConsoleInput(item) },
                                label = { Text(item, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    if (panel.consoleQuickCommands.isEmpty()) {
                        Text(
                            "还没有常用命令，点「添加」建一条",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.text.copy(alpha = 0.5f),
                        )
                    }
                }
                // 刚删过一条时给一条撤销：删除是立即写盘的，没有这个就真丢了。
                lastRemovedCommand?.let { removed ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已删除：$removed",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.text.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            viewModel.aiStudioAddConsoleQuickCommand(removed)
                            lastRemovedCommand = null
                        }) { Text("撤销") }
                    }
                }
            }
        }

        panel.error?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 终端配色主题。
 *
 * 终端需要固定的"深色背景"（不管 App 是亮色还是暗色），且输入行要与输出区有可见层次差。
 * 每套主题自带 16 色 ANSI 调色板——`ls`、`git`、彩色提示符都靠它上色。
 */
private data class TerminalTheme(
    val id: String,
    val label: String,
    val bg: Color,
    val inputBg: Color,
    val text: Color,
    val prompt: Color,
    val ansi: List<Color>,
)

private fun theme(
    id: String,
    label: String,
    bg: Long,
    inputBg: Long,
    text: Long,
    prompt: Long,
    ansi: List<Long>,
) = TerminalTheme(id, label, Color(bg), Color(inputBg), Color(text), Color(prompt), ansi.map { Color(it) })

/**
 * 终端配色主题（与参考 App 的常见主题对应）。顺序即选择器里的展示顺序，
 * 第一个是默认。每套的 16 色为：黑 红 绿 黄 蓝 洋红 青 白 亮黑 亮红 亮绿 亮黄 亮蓝 亮洋红 亮青 亮白。
 */
private val TERMINAL_THEMES = listOf(
    theme(
        "github-dark", "GitHub Dark",
        bg = 0xFF0D1117, inputBg = 0xFF161B22, text = 0xFFC9D1D9, prompt = 0xFF58A6FF,
        ansi = listOf(
            0xFF484F58, 0xFFFF7B72, 0xFF3FB950, 0xFFD29922, 0xFF58A6FF, 0xFFBC8CFF, 0xFF39C5CF, 0xFFB1BAC4,
            0xFF6E7681, 0xFFFFA198, 0xFF56D364, 0xFFE3B341, 0xFF79C0FF, 0xFFD2A8FF, 0xFF56D4DD, 0xFFF0F6FC,
        ),
    ),
    theme(
        "dracula", "Dracula",
        bg = 0xFF282A36, inputBg = 0xFF343746, text = 0xFFF8F8F2, prompt = 0xFF50FA7B,
        ansi = listOf(
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
        ),
    ),
    theme(
        "one-dark", "One Dark",
        bg = 0xFF282C34, inputBg = 0xFF31353F, text = 0xFFABB2BF, prompt = 0xFF61AFEF,
        ansi = listOf(
            0xFF282C34, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
            0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFFFFFFF,
        ),
    ),
    theme(
        "monokai", "Monokai",
        bg = 0xFF272822, inputBg = 0xFF32332B, text = 0xFFF8F8F2, prompt = 0xFFA6E22E,
        ansi = listOf(
            0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
            0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5,
        ),
    ),
    theme(
        "termius-dark", "Termius Dark",
        bg = 0xFF17191F, inputBg = 0xFF242833, text = 0xFFD6DEEB, prompt = 0xFF4DD0D8,
        ansi = listOf(
            0xFF3F3F46, 0xFFF07178, 0xFFA5D6A7, 0xFFFFCB6B, 0xFF82AAFF, 0xFFC792EA, 0xFF89DDFF, 0xFFD6DEEB,
            0xFF6B7280, 0xFFFF9DA3, 0xFFC3E88D, 0xFFFFE082, 0xFFA4C8FF, 0xFFE0AAFF, 0xFFB2EBF2, 0xFFFFFFFF,
        ),
    ),
)

/** 默认主题（偏好为空时用）。 */
private val DEFAULT_TERMINAL_THEME = TERMINAL_THEMES.first()

private fun resolveTerminalTheme(id: String): TerminalTheme =
    TERMINAL_THEMES.firstOrNull { it.id == id } ?: DEFAULT_TERMINAL_THEME

/**
 * ANSI 转义序列匹配器。分组 1 是 SGR 参数（`ESC[<params>m`），其余分支为待丢弃的控制序列。
 *
 * - CSI（除 SGR 外）：`ESC[?2004h`、`ESC[K` 等
 * - OSC：`ESC]0;title` 由 BEL 或 ST 结束
 * - 双字符转义：`ESC(`、`ESC=` 等
 */
private val TERMINAL_ESCAPE = Regex(
    "\u001B\\[([0-9;]*)m" +
        "|\u001B\\[[0-9;?]*[ -/]*[@-~]" +
        "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
        "|\u001B[@-Z\\\\-_]",
)

/** 应用一条 SGR 参数串（如 `01;34` 或 `38;5;208`），返回更新后的前景色与加粗。 */
private fun applySgr(params: String, palette: List<Color>, fg: Color?, bold: Boolean): Pair<Color?, Boolean> {
    var newFg = fg
    var newBold = bold
    val parts = params.split(';')
    var index = 0
    while (index < parts.size) {
        val code = parts[index].toIntOrNull() ?: 0
        when (code) {
            0 -> { newFg = null; newBold = false }
            1 -> newBold = true
            22 -> newBold = false
            39 -> newFg = null
            in 30..37 -> newFg = palette.getOrNull(code - 30)
            in 90..97 -> newFg = palette.getOrNull(code - 90 + 8)
            // 38;5;n 256 色 / 38;2;r;g;b 真彩色——现代 LS_COLORS 会用到。
            38 -> {
                when (parts.getOrNull(index + 1)?.toIntOrNull()) {
                    5 -> {
                        val n = parts.getOrNull(index + 2)?.toIntOrNull()
                        if (n != null) newFg = colorFrom256(n, palette)
                        index += 2
                    }
                    2 -> {
                        val r = parts.getOrNull(index + 2)?.toIntOrNull()
                        val g = parts.getOrNull(index + 3)?.toIntOrNull()
                        val b = parts.getOrNull(index + 4)?.toIntOrNull()
                        if (r != null && g != null && b != null) newFg = Color(r, g, b)
                        index += 4
                    }
                }
            }
        }
        index += 1
    }
    return newFg to newBold
}

/** 256 色索引 → 颜色（0-15 用主题调色板，16-231 为 6x6x6 色阶，232-255 为灰阶）。 */
private fun colorFrom256(index: Int, palette: List<Color>): Color = when {
    index < 16 -> palette.getOrElse(index) { palette.last() }
    index < 232 -> {
        val n = index - 16
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(level(n / 36), level((n % 36) / 6), level(n % 6))
    }
    else -> {
        val v = 8 + (index - 232) * 10
        Color(v, v, v)
    }
}

/**
 * 把一行带 ANSI SGR 序列的终端文本转成 [AnnotatedString]。
 *
 * 用正则一次扫完：SGR（颜色/加粗）转成 SpanStyle，其余控制序列直接丢弃。
 * 相比之前手写 indexOf 的写法，这里**不会把未匹配的 `ESC[` 残片当正文输出**——
 * 之前遇到 `ESC[` 后面没有 `m`（跨帧被切开的序列、`ESC[K` 清行等）时，会把 `[`
 * 及其后内容原样渲染出来，终端里就会冒出一串 `[0m`、`[01;34m` 之类的乱码。
 * 任何裸 ESC 也在末尾统一清掉，绝不进入正文。
 */
private fun terminalAnnotatedLine(line: String, theme: TerminalTheme): AnnotatedString = buildAnnotatedString {
    var fg: Color? = null
    var bold = false
    var last = 0
    for (match in TERMINAL_ESCAPE.findAll(line)) {
        if (match.range.first > last) {
            appendStyled(line.substring(last, match.range.first), fg, bold, theme)
        }
        val sgr = match.groups[1]
        if (sgr != null) {
            val (f, b) = applySgr(sgr.value, theme.ansi, fg, bold)
            fg = f
            bold = b
        }
        last = match.range.last + 1
    }
    if (last < line.length) appendStyled(line.substring(last), fg, bold, theme)
}

/** 追加一段普通文本，并清掉任何漏网的不可见控制字符（如孤立的 ESC），避免渲染成乱码方块。 */
private fun AnnotatedString.Builder.appendStyled(
    text: String,
    fg: Color?,
    bold: Boolean,
    theme: TerminalTheme,
) {
    val clean = text.filter { it == '\t' || it.code >= 32 }
    if (clean.isEmpty()) return
    withStyle(SpanStyle(color = fg ?: theme.text, fontWeight = if (bold) FontWeight.Bold else null)) {
        append(clean)
    }
}
