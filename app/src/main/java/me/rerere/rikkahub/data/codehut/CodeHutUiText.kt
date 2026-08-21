/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

private const val DEFAULT_CODE_HUT_UI_TEXT_LIMIT = 2_000

/**
 * Sanitizes untrusted Harness diagnostics before they reach any Code Hut surface.
 *
 * Harness output can contain provider credentials in errors, command echoes, paths, or task
 * results. Keep this as the single display boundary instead of relying on each caller to
 * remember its own regex and length limit.
 */
fun redactCodeHutUiText(
    value: String,
    maxChars: Int = DEFAULT_CODE_HUT_UI_TEXT_LIMIT,
): String {
    require(maxChars > 0) { "maxChars must be positive" }

    val redactedLabels = labelledSecretPatterns.fold(value) { current, pattern ->
        current.replace(pattern) { match ->
            val label = match.groups[1]?.value.orEmpty()
            val separator = match.groups[2]?.value.orEmpty()
            val quote = match.groups[3]?.value.orEmpty()
            "$label$separator$quote[REDACTED]"
        }
    }
    val redactedBareTokens = bareTokenPatterns.fold(redactedLabels) { current, pattern ->
        current.replace(pattern, "[REDACTED]")
    }
    return redactedBareTokens.take(maxChars)
}

private val labelledSecretPatterns = listOf(
    Regex(
        pattern = "(?i)\\b(authorization)(\\s*[:=]\\s*)([\\\"']?)(bearer\\s+[^\\\"'\\s,;}]+)",
    ),
    Regex(
        pattern = "(?i)\\b(bearer)(\\s+)([\\\"']?)([^\\\"'\\s,;}]+)",
    ),
    Regex(
        pattern = "(?i)\\b(api\\s*key|api[_-]?key|token|password|passwd|secret)(\\s*[\\\"']?\\s*[:=]\\s*)([\\\"']?)([^\\\"'\\s,;}]+)",
    ),
)

private val bareTokenPatterns = listOf(
    Regex("(?i)\\bgh[pousr]_[A-Za-z0-9_]+\\b"),
    Regex("(?i)\\bsk-[A-Za-z0-9_-]+\\b"),
    Regex("(?i)\\bxox[baprs]-[A-Za-z0-9-]+\\b"),
)
