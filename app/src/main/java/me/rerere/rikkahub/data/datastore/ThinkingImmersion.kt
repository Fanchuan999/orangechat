/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

private const val DefaultThinkingUserAlias = "宝宝"
private const val DefaultThinkingAssistantAlias = "Daddy"

private val englishUserRole = Regex(
    """(?i)(?<![a-z])user(?![a-z]|\s*(?:id|interface|agent|data|request))"""
)
private val englishAssistantRole = Regex("""(?i)(?<![a-z])assistant(?![a-z])""")
private val englishAiRole = Regex("""(?i)(?<![a-z])ai(?![a-z]|\s*(?:模型|model))""")
private val chineseUserRole = Regex("""用户(?!界面|体验|输入|协议|代理|数据|请求|端|权限|标识|画像)""")
private val chineseAssistantRole = Regex("""助手(?!功能|接口|模式|工具|配置|消息|模块|服务)""")

private fun String.orThinkingAlias(defaultValue: String): String = trim().ifBlank { defaultValue }

internal fun String.parseThinkingAliases(): List<String> =
    split(',', '，', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)

fun DisplaySetting.thinkingImmersionPrompt(): String {
    if (!thinkingImmersionEnabled) return ""

    val userAlias = thinkingUserAlias.orThinkingAlias(DefaultThinkingUserAlias)
    val assistantAlias = thinkingAssistantAlias.orThinkingAlias(DefaultThinkingAssistantAlias)
    return """
        思考约定：内部思考优先使用简体中文；提及聊天对象时称呼她为“$userAlias”，自称“$assistantAlias”。
        不要在思考中使用“用户/user/User”“助手/assistant/AI”等泛称。
        此约定只影响内部思考，不改变最终回复的语言或内容。
    """.trimIndent()
}

fun DisplaySetting.formatThinkingForDisplay(raw: String): String {
    if (!thinkingImmersionEnabled) return raw

    val userAlias = thinkingUserAlias.orThinkingAlias(DefaultThinkingUserAlias)
    val assistantAlias = thinkingAssistantAlias.orThinkingAlias(DefaultThinkingAssistantAlias)
    val oldNames = buildList {
        add(userNickname)
        addAll(thinkingUserAlternateNames.parseThinkingAliases())
    }
        .map(String::trim)
        .filter { it.isNotBlank() && !it.equals(userAlias, ignoreCase = true) }
        .distinctBy(String::lowercase)
        .sortedByDescending(String::length)

    return oldNames.fold(raw) { text, oldName ->
        text.replace(Regex(Regex.escape(oldName), RegexOption.IGNORE_CASE), userAlias)
    }
        .replace(chineseUserRole, userAlias)
        .replace(englishUserRole, userAlias)
        .replace(chineseAssistantRole, assistantAlias)
        .replace(englishAssistantRole, assistantAlias)
        .replace(englishAiRole, assistantAlias)
}
