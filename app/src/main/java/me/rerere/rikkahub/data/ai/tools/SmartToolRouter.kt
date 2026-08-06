package me.rerere.rikkahub.data.ai.tools

/**
 * Chooses the optional MCP/plugin capability surface for one normal chat send.
 *
 * The router deliberately uses local keyword matching only: it never adds a model call,
 * and a false negative can always be retried with the one-send full-tools override.
 */
object SmartToolRouter {
    internal enum class Scene {
        HEALTH,
        MAPS,
        READING,
        WEATHER,
        HYDRATION,
        TODO,
        JOURNAL,
        CYCLE,
        MOMENTS,
        SHELL,
        TIME,
        STORY,
        WALLET,
        DISCIPLINE,
    }

    private val coreMemoryMcpTools = setOf(
        "breath",
        "breath_search",
        "breath_advanced",
        "pulse",
        "dream",
        "source_read",
        "letter_read",
        "hold",
        "grow",
        "letter_write",
        "trace",
        "plan",
        "anchor",
        "release",
        "i",
    )

    private val scenePluginIds = mapOf(
        Scene.READING to setOf("com.daddy.yingfan.coreading"),
        Scene.WEATHER to setOf("com.daddy.weather"),
        Scene.HYDRATION to setOf("com.daddy.yingfan.water"),
        Scene.TODO to setOf("com.fanchuan.orangechat.todo", "com.fanchuan.orangechat.sharednotebook"),
        Scene.JOURNAL to setOf("com.daddy.yingfan.daddyjournal"),
        Scene.CYCLE to setOf("com.daddy.yingfan.cycle"),
        Scene.MOMENTS to setOf("com.daddy.yingfan.moments"),
        Scene.SHELL to setOf("com.daddy.termux"),
        Scene.TIME to setOf("com.daddy.yingfan.strict_time"),
        Scene.STORY to setOf("com.daddy.story"),
        Scene.WALLET to setOf("com.yingfan.orangechat.wallet"),
        Scene.DISCIPLINE to setOf("com.daddy.fanfan.disciplinecounter"),
    )

    fun select(
        message: String,
        throttlingEnabled: Boolean,
        forceFullTools: Boolean,
    ): SmartToolSelection {
        if (!throttlingEnabled || forceFullTools) {
            return SmartToolSelection(includeAllTools = true, scenes = emptySet())
        }

        val normalized = message.lowercase()
        return SmartToolSelection(
            includeAllTools = false,
            scenes = buildSet {
                if (normalized.containsAny(healthKeywords)) add(Scene.HEALTH)
                if (normalized.containsAny(mapKeywords)) add(Scene.MAPS)
                if (normalized.containsAny(readingKeywords)) add(Scene.READING)
                if (normalized.containsAny(weatherKeywords)) add(Scene.WEATHER)
                if (normalized.containsAny(hydrationKeywords)) add(Scene.HYDRATION)
                if (normalized.containsAny(todoKeywords)) add(Scene.TODO)
                if (normalized.containsAny(journalKeywords)) add(Scene.JOURNAL)
                if (normalized.containsAny(cycleKeywords)) add(Scene.CYCLE)
                if (normalized.containsAny(momentsKeywords)) add(Scene.MOMENTS)
                if (normalized.containsAny(shellKeywords)) add(Scene.SHELL)
                if (normalized.containsAny(timeKeywords)) add(Scene.TIME)
                if (normalized.containsAny(storyKeywords)) add(Scene.STORY)
                if (normalized.containsAny(walletKeywords)) add(Scene.WALLET)
                if (normalized.containsAny(disciplineKeywords)) add(Scene.DISCIPLINE)
            },
        )
    }

    class SmartToolSelection internal constructor(
        val includeAllTools: Boolean,
        private val scenes: Set<Scene>,
    ) {
        val allowedPluginIds: Set<String> = scenes.flatMapTo(linkedSetOf()) { scenePluginIds[it].orEmpty() }

        fun allowsPlugin(pluginId: String): Boolean = includeAllTools || pluginId in allowedPluginIds

        fun allowsMcpTool(rawToolName: String): Boolean {
            if (includeAllTools) return true

            val name = rawToolName.lowercase()
            if (name in coreMemoryMcpTools) return true

            return scenes.any { scene ->
                when (scene) {
                    Scene.HEALTH -> name.containsAny(healthToolNameParts)
                    Scene.MAPS -> name.containsAny(mapToolNameParts)
                    else -> false
                }
            }
        }
    }

    private fun String.containsAny(keywords: Set<String>): Boolean = keywords.any(::contains)

    private val healthKeywords = setOf(
        "健康", "睡眠", "睡了", "心率", "步数", "血氧", "压力", "卡路里", "热量", "运动",
        "手环", "gadgetbridge", "电量", "体温", "心跳", "health", "sleep", "heart rate", "steps",
        "spo2", "stress", "calorie", "battery",
    )
    private val mapKeywords = setOf(
        "路线", "导航", "怎么走", "去哪里", "附近", "地址", "位置", "地图", "高德", "路程",
        "公交", "地铁", "打车", "路线规划", "route", "navigation", "nearby", "map", "amap",
    )
    private val readingKeywords = setOf("共读", "书房", "这本书", "这一章", "章节", "读书", "批注", "书签", "阅读")
    private val weatherKeywords = setOf("天气", "下雨", "温度", "气温", "weather")
    private val hydrationKeywords = setOf("喝水", "饮水", "水杯", "补水")
    private val todoKeywords = setOf("待办", "任务", "清单", "提醒我", "记得要", "todo", "to-do")
    private val journalKeywords = setOf("日记", "今天发生", "写下来", "日记候选")
    private val cycleKeywords = setOf("经期", "姨妈", "月经", "周期")
    private val momentsKeywords = setOf("朋友圈", "动态", "发个动态")
    private val shellKeywords = setOf("termux", "终端", "命令", "运行脚本", "shell")
    private val timeKeywords = setOf("几点", "时间", "倒计时", "快开始", "迟到", "提醒", "还有多久")
    private val storyKeywords = setOf("写故事", "续写", "剧情", "故事生成")
    private val walletKeywords = setOf("余额", "钱包", "记账", "花了", "收入")
    private val disciplineKeywords = setOf("惩罚", "打卡", "违规", "纪律")

    private val healthToolNameParts = setOf(
        "health", "heart", "sleep", "step", "spo2", "stress", "calorie", "battery", "device",
    )
    private val mapToolNameParts = setOf(
        "route", "navigation", "direction", "nearby", "place", "poi", "geocode", "location", "amap",
    )
}
