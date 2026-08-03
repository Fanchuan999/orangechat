/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * A small, entirely local state machine used as a continuity cue for a companion.
 *
 * The values are deliberately not a diagnosis of the user or an emotion detector. They only
 * evolve from elapsed time and successful conversation events, so they are predictable,
 * private, and free to run.
 */
@Serializable
data class CompanionMoodSetting(
    val enabled: Boolean = true,
    /**
     * Let the local state decide whether a scheduled wake-up is worth a model call.
     * Device-event / aggressive-mode wake-ups deliberately bypass this gate.
     */
    val proactiveRhythmEnabled: Boolean = true,
    val expressionStrength: Float = 0.5f,
    val state: CompanionMoodState = CompanionMoodState(),
)

@Serializable
data class CompanionMoodState(
    /** Desire to reconnect after some time apart. */
    val connection: Float = 0.25f,
    /** A tiny confidence / self-possession axis, centred around zero. */
    val pride: Float = 0f,
    /** Pleasantness, centred around zero. */
    val valence: Float = 0.12f,
    /** Energy of expression. */
    val arousal: Float = 0.12f,
    /** How much the current conversation has the companion's attention. */
    val immersion: Float = 0.2f,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/** Evolves the state without writing anything. This makes opening the app free and side-effect free. */
fun evolveCompanionMood(state: CompanionMoodState, nowMillis: Long = System.currentTimeMillis()): CompanionMoodState {
    if (nowMillis <= state.updatedAtMillis) return state

    // A month is more than enough for the state to settle. Capping also protects against bad clocks.
    val hours = ((nowMillis - state.updatedAtMillis).toDouble() / HOUR_MILLIS).coerceIn(0.0, 24.0 * 31)
    if (hours == 0.0) return state

    fun decayTo(value: Float, neutral: Float, hourlyFactor: Double): Float =
        (
            neutral.toDouble() +
                (value - neutral).toDouble() * hourlyFactor.pow(hours)
            ).toFloat().coerceIn(-1f, 1f)

    // The curve and thresholds below are adapted from the MIT-licensed jiwen project
    // (https://github.com/ClaraShafiq/jiwen). We keep the state fully local and use
    // it only as a gate before an existing scheduled model request.
    val reconnection = if (hours <= 0.5) 0f else ((hours - 0.5) * 0.045).toFloat()
    val nextConnection = (state.connection + reconnection).coerceIn(0f, 0.92f)
    val defending = nextConnection >= 0.35f
    val nextPride = if (defending) {
        moveToward(state.pride, target = 0.50f, amount = (hours * 0.03).toFloat())
    } else {
        decayTo(state.pride, neutral = 0f, hourlyFactor = 0.88)
    }
    val nextArousal = if (nextConnection >= 0.42f) {
        (decayTo(state.arousal, neutral = 0.12f, hourlyFactor = 0.72) + (hours * 0.018).toFloat())
            .coerceIn(0f, 1f)
    } else {
        decayTo(state.arousal, neutral = 0.12f, hourlyFactor = 0.72)
    }
    return state.copy(
        connection = nextConnection,
        pride = nextPride,
        valence = decayTo(
            state.valence,
            neutral = 0f,
            hourlyFactor = if (nextConnection >= 0.42f) 0.96 else 0.90,
        ),
        arousal = nextArousal,
        immersion = (state.immersion.toDouble() * 0.58.pow(hours)).toFloat().coerceIn(0f, 1f),
        updatedAtMillis = nowMillis,
    )
}

fun CompanionMoodState.afterUserMessage(nowMillis: Long = System.currentTimeMillis()): CompanionMoodState {
    val current = evolveCompanionMood(this, nowMillis)
    return current.copy(
        // A real reply settles the desire to reconnect; sending a proactive message does not.
        connection = 0f,
        pride = (current.pride + 0.03f).coerceIn(-1f, 1f),
        valence = (current.valence + 0.09f).coerceIn(-1f, 1f),
        arousal = (current.arousal + 0.08f).coerceIn(0f, 1f),
        immersion = (current.immersion + 0.32f).coerceIn(0f, 1f),
        updatedAtMillis = nowMillis,
    )
}

fun CompanionMoodState.afterAssistantMessage(nowMillis: Long = System.currentTimeMillis()): CompanionMoodState {
    val current = evolveCompanionMood(this, nowMillis)
    return current.copy(
        connection = (current.connection - 0.06f).coerceAtLeast(0f),
        valence = (current.valence + 0.03f).coerceIn(-1f, 1f),
        arousal = (current.arousal - 0.03f).coerceIn(0f, 1f),
        immersion = (current.immersion + 0.08f).coerceIn(0f, 1f),
        updatedAtMillis = nowMillis,
    )
}

fun CompanionMoodState.afterProactiveMessage(nowMillis: Long = System.currentTimeMillis()): CompanionMoodState {
    val current = evolveCompanionMood(this, nowMillis)
    return current.copy(
        connection = (current.connection - 0.16f).coerceAtLeast(0f),
        arousal = (current.arousal + 0.03f).coerceIn(0f, 1f),
        immersion = (current.immersion + 0.1f).coerceIn(0f, 1f),
        updatedAtMillis = nowMillis,
    )
}

/** A local decision; only [Contact] is allowed to spend tokens. */
enum class CompanionProactiveDecision {
    Contact,
    Observe,
    FindActivity,
}

/**
 * Turn the five small local axes into a deterministic contact decision.
 *
 * Below 0.20 Daddy merely notices the quiet; between 0.20 and 0.35 it still
 * waits. A high-pride, not-yet-urgent state becomes a private "find activity"
 * state instead of an interruption. At 0.50, it is okay to reach out.
 */
fun CompanionMoodSetting.proactiveDecision(nowMillis: Long = System.currentTimeMillis()): CompanionProactiveDecision {
    if (!enabled || !proactiveRhythmEnabled) return CompanionProactiveDecision.Contact
    val current = evolveCompanionMood(state, nowMillis)
    return when {
        current.connection < 0.35f -> CompanionProactiveDecision.Observe
        current.connection < 0.50f && current.pride >= 0.50f && current.immersion < 0.20f -> {
            CompanionProactiveDecision.FindActivity
        }
        else -> CompanionProactiveDecision.Contact
    }
}

private fun moveToward(value: Float, target: Float, amount: Float): Float = when {
    value < target -> (value + amount).coerceAtMost(target)
    value > target -> (value - amount).coerceAtLeast(target)
    else -> value
}

fun CompanionMoodSetting.expressionLabel(): String = when {
    expressionStrength < 0.42f -> "克制"
    expressionStrength < 0.72f -> "轻柔"
    else -> "明显"
}

fun CompanionMoodSetting.currentSummary(nowMillis: Long = System.currentTimeMillis()): String {
    val current = evolveCompanionMood(state, nowMillis)
    val mood = when {
        current.valence > 0.32f -> "心情轻快"
        current.valence < -0.32f -> "有点安静"
        else -> "心绪平稳"
    }
    val connection = when {
        current.connection > 0.75f -> "有些想靠近你"
        current.connection > 0.45f -> "期待和你聊聊"
        else -> "陪在当下"
    }
    return "$mood，$connection"
}

/** A short, natural-language suffix. No axes or raw values are ever sent to the model. */
fun CompanionMoodSetting.promptContext(
    nowMillis: Long = System.currentTimeMillis(),
    proactive: Boolean = false,
): String {
    if (!enabled) return ""

    val current = evolveCompanionMood(state, nowMillis)
    val mood = when {
        current.valence > 0.32f -> "轻快"
        current.valence < -0.32f -> "安静"
        else -> "平静"
    }
    val connection = when {
        current.connection > 0.75f -> "有点想和用户靠近"
        current.connection > 0.45f -> "期待继续聊聊"
        else -> "安心陪在当下"
    }
    val energy = when {
        current.arousal > 0.62f -> "明亮有精神"
        current.arousal < 0.22f -> "从容克制"
        else -> "自然温和"
    }
    val expression = when {
        expressionStrength < 0.42f -> "保持克制，不强行亲昵"
        expressionStrength < 0.72f -> "自然温柔，可有一点点主动"
        else -> "可以更亲近一点，但仍尊重边界"
    }
    val proactiveHint = if (proactive && current.connection > 0.62f) {
        "若有合适的话题，可自然地先开口；没话题仍可跳过。"
    } else {
        ""
    }
    return "[持续情绪，仅作语气参考且不要提及] 此刻：$mood，$connection，$energy；表达：$expression。保持原有人格、边界与对话目标。$proactiveHint"
}

private const val HOUR_MILLIS = 60.0 * 60.0 * 1000.0
