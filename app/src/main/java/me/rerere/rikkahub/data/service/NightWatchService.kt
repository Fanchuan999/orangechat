/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.DEVICE_EVENT_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.datastore.NightWatchSetting
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "NightWatchService"

/**
 * Classifies only the text the user actually sent in a chat. It deliberately never examines
 * model output or automatic trigger instructions, so a generated "晚安" cannot arm the watch.
 */
internal object NightWatchMessageClassifier {
    enum class Action { Arm, Disarm, None }

    private val disarmPhrases = listOf(
        "我不睡了", "不睡了", "我起床了", "起床了", "别管我了", "别管我", "不要管我", "不用管我",
        "别催我", "别逮我"
    )
    private val negativeSleepPhrases = listOf("不睡", "没睡", "睡不着", "不想睡")
    private val bedtimePhrases = listOf("晚安", "去睡", "先睡", "睡觉", "睡了", "睡啦", "睡咯", "我要睡", "准备睡", "该睡")

    fun classify(text: String): Action {
        val normalized = text.lowercase().replace(Regex("\\s+"), "")
        if (normalized.isBlank()) return Action.None
        if (disarmPhrases.any(normalized::contains)) return Action.Disarm
        if (negativeSleepPhrases.any(normalized::contains)) return Action.None
        return if (bedtimePhrases.any(normalized::contains)) Action.Arm else Action.None
    }
}

/**
 * Persistent runtime state is intentionally private to this phone rather than included in a
 * backup: restoring an APK must not resurrect last night's watch on a new day/device.
 */
object NightWatchManager {
    private const val PREFS_NAME = "night_watch"
    private const val KEY_ARMED_AT = "armed_at"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_LAST_TRIGGERED_AT = "last_triggered_at"
    private const val KEY_ASSISTANT_ID = "assistant_id"
    private const val KEY_CONVERSATION_ID = "conversation_id"
    private const val KEY_FIRST_CHECK_MINUTES = "first_check_minutes"
    private const val KEY_REPEAT_INTERVAL_MINUTES = "repeat_interval_minutes"

    data class Snapshot(
        val armedAt: Long,
        val expiresAt: Long,
        val lastTriggeredAt: Long,
        val assistantId: String,
        val conversationId: String,
        val firstCheckMinutes: Int,
        val repeatIntervalMinutes: Int,
    ) {
        fun firstEligibleAt(): Long = armedAt + firstCheckMinutes * 60_000L
        fun nextEligibleAt(): Long = if (lastTriggeredAt > 0L) {
            lastTriggeredAt + repeatIntervalMinutes * 60_000L
        } else {
            firstEligibleAt()
        }
    }

    fun onActualUserMessage(
        context: Context,
        setting: NightWatchSetting,
        assistantId: String,
        conversationId: String,
        text: String,
    ) {
        when (NightWatchMessageClassifier.classify(text)) {
            NightWatchMessageClassifier.Action.Disarm -> disarm(context)
            NightWatchMessageClassifier.Action.Arm -> if (setting.enabled) {
                arm(context, setting, assistantId, conversationId)
            }
            NightWatchMessageClassifier.Action.None -> Unit
        }
    }

    fun arm(context: Context, setting: NightWatchSetting, assistantId: String, conversationId: String) {
        val now = System.currentTimeMillis()
        val firstCheckMinutes = setting.firstCheckMinutes.coerceAtLeast(1)
        val repeatIntervalMinutes = setting.repeatIntervalMinutes.coerceAtLeast(1)
        prefs(context).edit()
            .putLong(KEY_ARMED_AT, now)
            .putLong(KEY_EXPIRES_AT, nextSixAmAfterToday(now))
            .putLong(KEY_LAST_TRIGGERED_AT, 0L)
            .putString(KEY_ASSISTANT_ID, assistantId)
            .putString(KEY_CONVERSATION_ID, conversationId)
            .putInt(KEY_FIRST_CHECK_MINUTES, firstCheckMinutes)
            .putInt(KEY_REPEAT_INTERVAL_MINUTES, repeatIntervalMinutes)
            .apply()
        startService(context)
        Log.i(TAG, "Night watch armed for conversation=$conversationId")
    }

    fun disarm(context: Context) {
        prefs(context).edit().clear().apply()
        context.stopService(Intent(context, NightWatchService::class.java))
        Log.i(TAG, "Night watch disarmed")
    }

    fun startIfArmed(context: Context) {
        if (readSnapshot(context) != null) startService(context)
    }

    fun isArmed(context: Context): Boolean = readSnapshot(context) != null

    fun readSnapshot(context: Context, now: Long = System.currentTimeMillis()): Snapshot? {
        val prefs = prefs(context)
        val armedAt = prefs.getLong(KEY_ARMED_AT, 0L)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val assistantId = prefs.getString(KEY_ASSISTANT_ID, null)
        val conversationId = prefs.getString(KEY_CONVERSATION_ID, null)
        if (armedAt <= 0L || expiresAt <= now || assistantId.isNullOrBlank() || conversationId.isNullOrBlank()) {
            if (armedAt > 0L) prefs.edit().clear().apply()
            return null
        }
        return Snapshot(
            armedAt = armedAt,
            expiresAt = expiresAt,
            lastTriggeredAt = prefs.getLong(KEY_LAST_TRIGGERED_AT, 0L),
            assistantId = assistantId,
            conversationId = conversationId,
            firstCheckMinutes = prefs.getInt(KEY_FIRST_CHECK_MINUTES, 10).coerceAtLeast(1),
            repeatIntervalMinutes = prefs.getInt(KEY_REPEAT_INTERVAL_MINUTES, 10).coerceAtLeast(1),
        )
    }

    fun markTriggered(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_TRIGGERED_AT, timestamp).apply()
    }

    private fun startService(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, NightWatchService::class.java))
        }.onFailure { Log.e(TAG, "Unable to start night watch service", it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * A watch belongs to the night in which it was armed.  Use an explicit timezone here so a
     * device timezone change cannot leave it active into the following afternoon.
     */
    private fun nextSixAmAfterToday(now: Long): Long = Calendar.getInstance(BEIJING_TIME_ZONE).run {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 6)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private val BEIJING_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
}

/**
 * A focused alternative to aggressive mode. It only runs after an actual user bedtime message.
 * It checks at fixed eligibility times and on unlock, so continuing to use the phone cannot keep
 * resetting a debounce timer indefinitely.
 */
class NightWatchService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 20006
        // Unlock broadcasts provide the immediate path. This is only a quiet fallback for
        // manufacturer-specific background behaviour; without it, a screen-off check whose
        // due time has passed would reschedule itself in a zero-delay loop.
        private const val MISSED_CHECK_RETRY_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evaluationMutex = Mutex()
    private var scheduledCheck: Job? = null
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        scheduleNextCheck()
        return START_STICKY
    }

    override fun onDestroy() {
        unlockReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        unlockReceiver = null
        scheduledCheck?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    scope.launch {
                        if (evaluateAndMaybeTrigger("解锁后仍在使用")) {
                            scheduleNextCheck()
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        unlockReceiver = receiver
    }

    private fun scheduleNextCheck() {
        scheduledCheck?.cancel()
        val snapshot = NightWatchManager.readSnapshot(this)
        if (snapshot == null) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val eligibleAt = snapshot.nextEligibleAt()
        val nextAt = when {
            eligibleAt > now -> eligibleAt
            else -> now + MISSED_CHECK_RETRY_MS
        }.coerceAtMost(snapshot.expiresAt)
        scheduledCheck = scope.launch {
            delay((nextAt - now).coerceAtLeast(0L))
            evaluateAndMaybeTrigger("守夜定时检查")
            scheduleNextCheck()
        }
    }

    private suspend fun evaluateAndMaybeTrigger(trigger: String): Boolean {
        return evaluationMutex.withLock {
            val now = System.currentTimeMillis()
            val snapshot = NightWatchManager.readSnapshot(this@NightWatchService, now) ?: run {
                stopSelf()
                return@withLock false
            }
            if (now < snapshot.nextEligibleAt()) return@withLock false
            if (!isUnlockedAndInteractive()) return@withLock false

            // Claim before starting the model work. A simultaneous unlock broadcast and scheduled
            // check can therefore never create two messages in the same ten-minute interval.
            NightWatchManager.markTriggered(this@NightWatchService, now)
            val appName = foregroundAppNameSince(snapshot.armedAt, now)
            val dateFormat = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val beijingNowFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val armedAtText = dateFormat.format(Date(snapshot.armedAt))
            val beijingNowText = beijingNowFormat.format(Date(now))
            val waitedMinutes = ((now - snapshot.armedAt) / 60_000L).coerceAtLeast(1L)
            val eventContext = buildString {
                appendLine("[晚安守夜触发]")
                appendLine("权威当前时间：北京时间 $beijingNowText。这是手机系统直接提供的准确时间。")
                appendLine("用户在 $armedAtText 明确说晚安后，已经过去约 $waitedMinutes 分钟；现在屏幕仍亮且已解锁，仍在使用手机。")
                appName?.let { appendLine("当前前台应用：$it") }
                appendLine("触发方式：$trigger。")
                appendLine("本次已经满足守夜提醒条件：请自然、简短地发一条关心或管作息的消息，不能回复 [PASS]。")
                appendLine("这是系统自动触发，不是用户的新发言；禁止编造、补全、引用或假设用户刚刚回复过任何内容，也不要暴露设备或应用数据来源。")
                appendLine("守夜尚未结束：必须以本行北京时间判断，禁止调用任何时间/日期工具，也绝不能把此刻说成白天或说“早上好”。")
            }
            val triggerIntent = Intent(this@NightWatchService, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_DEVICE_EVENT_CONTEXT, eventContext)
                putExtra(ProactiveMessageTriggerService.EXTRA_NIGHT_WATCH_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_TARGET_ASSISTANT_ID, snapshot.assistantId)
                putExtra(ProactiveMessageTriggerService.EXTRA_TARGET_CONVERSATION_ID, snapshot.conversationId)
            }
            ContextCompat.startForegroundService(this@NightWatchService, triggerIntent)
            Log.i(TAG, "Night watch triggered ($trigger), app=${appName ?: "unknown"}")
            true
        }
    }

    private fun isUnlockedAndInteractive(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return powerManager?.isInteractive == true && keyguardManager?.isKeyguardLocked != true
    }

    private fun foregroundAppNameSince(startTime: Long, now: Long): String? {
        if (!SystemTools.hasAppUsagePermission(this)) return null
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        return runCatching {
            val events = usageStats.queryEvents(startTime - 30_000L, now)
            val event = UsageEvents.Event()
            var packageName: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val foreground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (foreground && event.packageName != this.packageName) {
                    packageName = event.packageName
                }
            }
            packageName?.let { pkg ->
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }
        }.getOrNull()
    }

    private fun startForegroundCompat() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, DEVICE_EVENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("Daddy 晚安守夜中")
            .setContentText("只在你说晚安后，安静守着作息")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
