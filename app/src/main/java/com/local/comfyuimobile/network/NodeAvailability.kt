package com.local.comfyuimobile.network

import org.json.JSONObject

/**
 * v0.1.89：节点缺失预检。
 *
 * ## 为什么需要它
 *
 * 以前的"缺节点"只能**事后**发现：参数页填好、点生成、服务器回 `node_errors`，
 * 用户才看到一堆红字。从网上扒来的工作流（尤其是 Anima 这种依赖好几个自定义
 * 节点包的）十有八九是缺的，可用户得先白等一次排队——云端平台上一次排队好几分钟。
 *
 * 而讽刺的是数据早就在手里了：[ComfyClient.objectInfo] 拉回来的那份
 * `/object_info` 就是这台服务器**所有已注册节点类型**的完整清单，以前却只用它
 * 判了个 `length() <= 0` 就扔掉。
 *
 * ## 设计取舍
 *
 * - **只取类型名集合，不留整份 JSON。** `/object_info` 动辄数 MB，长期驻留内存
 *   不划算；几千个类型名字符串则毫无压力，且这个集合在工作流加载时被反复查询。
 * - **不阻断生成。** 有些节点类型确实存在于运行时却不在 object_info 里（老的
 *   `Note`、某些前端专属节点），一刀切禁掉生成会误伤。这里只负责"把问题说清楚"，
 *   拦不拦由用户自己判断。
 */
object NodeAvailability {

    /**
     * 从 `/object_info` 的响应里抽出所有已注册的节点类型名。
     *
     * 结构是 `{ "CheckpointLoaderSimple": {...}, "KSampler": {...}, ... }`，
     * 键即类型名。拿不到合法对象时返回 null，表示"这次没查到"——与"查到 0 个"
     * 必须区分开：前者不该弹任何警告，后者才是真的有问题。
     */
    fun parseCatalog(infoJson: String): Set<String>? {
        val root = runCatching { JSONObject(infoJson) }.getOrNull() ?: return null
        if (root.length() == 0) return null
        val types = HashSet<String>(root.length() * 2)
        val keys = root.keys()
        while (keys.hasNext()) types.add(keys.next())
        return types.takeIf { it.isNotEmpty() }
    }

    /**
     * 找出工作流里用到、但服务器上没注册的节点类型。
     *
     * @param usedTypes 工作流里出现的节点类型（可含重复，会被去重）
     * @param catalog   [parseCatalog] 的结果；null 表示尚未查到，此时一律返回空列表
     *                  —— 宁可漏报也不能在没拿到清单时冤枉用户。
     * @return 缺失的类型名，按字典序排列，保证提示文案稳定不跳动
     */
    fun findMissing(usedTypes: Collection<String>, catalog: Set<String>?): List<String> {
        if (catalog.isNullOrEmpty()) return emptyList()
        return usedTypes
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            // 前端专属的占位节点不在 object_info 里，但对生成毫无影响，不该报。
            .filterNot { isClientOnly(it) }
            .distinct()
            .filterNot { it in catalog }
            .sorted()
            .toList()
    }

    /**
     * 这些是 ComfyUI 前端自己的节点，本来就不进 `/object_info`。
     * 报出来只会制造噪音，让用户去装一个根本不存在的东西。
     */
    private val CLIENT_ONLY = setOf(
        "Note",
        "MarkdownNote",
        "Reroute",
        "PrimitiveNode",
        "Bookmark (rgthree)",
    )

    fun isClientOnly(type: String): Boolean = type in CLIENT_ONLY

    /** 缺失节点的提示文案；没有缺失时返回 null。 */
    fun describe(missing: List<String>): String? {
        if (missing.isEmpty()) return null
        val shown = missing.take(MAX_LISTED)
        val suffix = if (missing.size > MAX_LISTED) " 等 ${missing.size} 个" else ""
        return buildString {
            append("这个工作流用了本机没有的节点：")
            append(shown.joinToString("、"))
            if (suffix.isNotEmpty()) append(suffix)
            append("。直接生成会失败，需要先在 ComfyUI 里装好对应的自定义节点包。")
        }
    }

    /**
     * 最多列几个。列满一屏的类型名除了吓人没有任何用处，用户需要的是
     * "缺东西了"这个判断，而不是一份安装清单。
     */
    private const val MAX_LISTED = 5
}
