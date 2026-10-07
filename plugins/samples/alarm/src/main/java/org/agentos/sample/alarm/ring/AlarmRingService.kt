package org.agentos.sample.alarm.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.schedule.SystemAlarmScheduler

/**
 * 响铃前台服务：到点后播放铃声（循环、渐强）、振动、挂全屏通知；关闭 / 贪睡 / 超时 / 闹钟被删除时收尾。
 * 状态都在主线程里改；外部（界面、MCP）经 [RingController] 发到主线程。
 */
class AlarmRingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Alarm? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rampJob: Job? = null
    private var timeoutJob: Job? = null
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RingController.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIRE -> onFire(intent.getStringExtra(EXTRA_ALARM_ID))
            ACTION_DISMISS -> dismiss()
            ACTION_SNOOZE -> snooze()
        }
        return START_NOT_STICKY
    }

    private fun onFire(id: String?) {
        val repository = AlarmGraph.get(this).repository
        val alarm = id?.let { repository.onFired(it) }
        if (alarm == null) {
            // 过期的触发（闹钟已被删除或关闭）。startForeground 必须调用一次再退出，否则系统会判定服务启动失败而崩溃
            val ringing = current
            if (ringing != null) {
                // 另一个闹钟正在响：别打断它，只把前台通知重新挂一次
                startForeground(
                    AlarmNotifications.ID_RINGING,
                    AlarmNotifications.ringing(this, ringing),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                return
            }
            startForeground(AlarmNotifications.ID_RINGING, AlarmNotifications.placeholder(this))
            Log.w(SystemAlarmScheduler.TAG, "stale fire for alarm $id, ignoring")
            finish()
            return
        }
        Log.i(SystemAlarmScheduler.TAG, "ringing alarm ${alarm.id} ${alarm.hour}:${alarm.minute} label='${alarm.label}'")
        // 之前还在响的（两个闹钟同一时间）：直接被新的取代
        stopSound()
        current = alarm
        RingController.state.value = alarm
        startForeground(
            AlarmNotifications.ID_RINGING,
            AlarmNotifications.ringing(this, alarm),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        acquireWakeLock()
        startSound(alarm)
        if (alarm.vibrate) startVibration()
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            Log.i(SystemAlarmScheduler.TAG, "ring timeout for alarm ${alarm.id}")
            AlarmNotifications.postMissed(this@AlarmRingService, listOf(alarm))
            finish()
        }
        // 正在响的闹钟被删除（界面或 MCP）时，立刻停
        watchJob?.cancel()
        watchJob = scope.launch {
            repository.alarms.collect { list ->
                if (list.none { it.id == alarm.id }) {
                    Log.i(SystemAlarmScheduler.TAG, "ringing alarm ${alarm.id} was deleted, stopping")
                    finish()
                }
            }
        }
    }

    internal fun dismiss() {
        if (current == null) return
        Log.i(SystemAlarmScheduler.TAG, "dismissed alarm ${current?.id}")
        finish()
    }

    internal fun snooze() {
        val alarm = current ?: return
        try {
            AlarmGraph.get(this).repository.snooze(alarm.id)
            Log.i(SystemAlarmScheduler.TAG, "snoozed alarm ${alarm.id} for ${alarm.snoozeMinutes} min")
        } catch (e: Exception) {
            Log.w(SystemAlarmScheduler.TAG, "snooze failed: ${e.message}")
        }
        finish()
    }

    private fun finish() {
        timeoutJob?.cancel()
        watchJob?.cancel()
        stopSound()
        current = null
        RingController.state.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        scope.cancel()
        if (RingController.service === this) RingController.service = null
        RingController.state.value = null
        super.onDestroy()
    }

    // ---- 声音、振动、唤醒锁 ----

    private fun startSound(alarm: Alarm) {
        val audio = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { audio.requestAudioFocus(it) }

        val candidates = buildList {
            alarm.ringtoneUri?.let { add(it.toUri()) }
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }.filterNotNull()
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@AlarmRingService, uri)
                    isLooping = true
                    setVolume(RAMP_START_VOLUME, RAMP_START_VOLUME)
                    prepare()
                    start()
                }
                Log.i(SystemAlarmScheduler.TAG, "playing ringtone $uri")
                break
            } catch (e: Exception) {
                Log.w(SystemAlarmScheduler.TAG, "cannot play $uri: ${e.message}")
                player?.release()
                player = null
            }
        }
        // 渐强：约 20 秒从小声到最大，不把人吓醒
        val p = player ?: return
        rampJob?.cancel()
        rampJob = scope.launch {
            val steps = 20
            for (i in 1..steps) {
                delay(1_000)
                val v = RAMP_START_VOLUME + (1f - RAMP_START_VOLUME) * i / steps
                runCatching { p.setVolume(v, v) }
            }
        }
    }

    private fun startVibration() {
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val pattern = longArrayOf(0, 700, 500, 700, 500, 1400, 900)
        vibrator.vibrate(
            VibrationEffect.createWaveform(pattern, 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
    }

    private fun stopSound() {
        rampJob?.cancel()
        runCatching { player?.stop() }
        player?.release()
        player = null
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun acquireWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentos-alarm:ring")
            .also { it.acquire(RING_TIMEOUT_MILLIS + 30_000) }
    }

    companion object {
        const val ACTION_FIRE = "org.agentos.sample.alarm.action.RING"
        const val ACTION_DISMISS = "org.agentos.sample.alarm.action.DISMISS"
        const val ACTION_SNOOZE = "org.agentos.sample.alarm.action.SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"

        /** 响铃最长 10 分钟，没人理会就静音并留一条“错过的闹钟”通知。 */
        const val RING_TIMEOUT_MILLIS = 10 * 60_000L
        private const val RAMP_START_VOLUME = 0.15f

        fun fireIntent(context: Context, alarmId: String): Intent =
            Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ALARM_ID, alarmId)
    }
}
