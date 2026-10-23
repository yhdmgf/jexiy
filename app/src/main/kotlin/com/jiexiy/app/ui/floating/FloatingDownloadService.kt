/*
 * jexiy (jexiy) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 jexiy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jiexiy.app.ui.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jiexiy.app.MainActivity
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.db.DownloadTaskEntity
import com.jiexiy.app.data.download.DownloadManagerHolder
import com.jiexiy.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 悬浮窗下载面板。
 *
 * 收起态是一个圆形下载图标；点开是「当前任务 + 全部暂停 / 全部继续 / 打开应用」的简单面板。
 * 用经典 View 搭（不用 Compose）：Service 里没有 Activity 的 LifecycleOwner，
 * ComposeView 还要额外挂 lifecycle，反而更重。
 *
 * 任务列表直接订阅 Room（`download_task`），暂停/继续走 [DownloadManagerHolder]；
 * 应用进程不在时没有 manager，按钮会置灰，但列表仍能显示。
 */
class FloatingDownloadService : Service() {

    private lateinit var windowManager: WindowManager
    private var root: View? = null
    private var panel: LinearLayout? = null
    private var taskContainer: LinearLayout? = null
    private var collapsedIcon: View? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showFloating()
        observeTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 悬浮窗 ----------

    private fun showFloating() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        // 收起态：圆形图标
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download_done)
            setBackgroundColor(Color.TRANSPARENT)
            background = circleBackground()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { togglePanel() }
        }
        container.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        collapsedIcon = icon

        // 展开态面板
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2FFFFFF"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        card.background = roundedBackground()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "下载任务"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "收起"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener { togglePanel() }
        })
        card.addView(header)

        taskContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        card.addView(taskContainer)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(actionButton("全部暂停") { pauseAll() }, weightParams())
        actions.addView(actionButton("全部继续") { resumeAll() }, weightParams())
        actions.addView(actionButton("打开应用") { openApp() }, weightParams())
        card.addView(actions)

        panel = card
        container.addView(card, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(200)
        }

        // 拖动：按住图标上下移动，松手吸附到屏幕内
        icon.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                        params.x = startX - dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick()
                    }
                }
                return true
            }
        })

        root = container
        runCatching { windowManager.addView(container, params) }
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        collapsedIcon?.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ---------- 任务列表 ----------

    private fun observeTasks() {
        val dao = AppDatabase.get(this).downloadTaskDao()
        collectJob = scope.launch {
            dao.observeAll().collectLatest { tasks ->
                val active = tasks.filter {
                    it.status == DownloadTaskEntity.STATUS_PENDING ||
                        it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                        it.status == DownloadTaskEntity.STATUS_PAUSED
                }.take(6)
                renderTasks(active)
            }
        }
    }

    private fun renderTasks(tasks: List<DownloadTaskEntity>) {
        val container = taskContainer ?: return
        container.removeAllViews()
        if (tasks.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "当前没有下载任务"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        tasks.forEach { task ->
            container.addView(TextView(this).apply {
                text = task.fileName
                textSize = 13f
                setTextColor(Color.parseColor("#1A1A1A"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                val percent = if (task.totalSize > 0) {
                    (task.downloadedSize * 1000 / task.totalSize).toInt().coerceIn(0, 1000)
                } else {
                    0
                }
                progress = percent
            }
            container.addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(4); bottomMargin = dp(10) })
        }
    }

    private fun pauseAll() {
        val manager = DownloadManagerHolder.manager ?: return
        scope.launch {
            val tasks = runCatching { manager.tasks.first() }.getOrDefault(emptyList())
            tasks.filter {
                it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                    it.status == DownloadTaskEntity.STATUS_PENDING
            }.forEach { manager.pause(it.id) }
        }
    }

    private fun resumeAll() {
        val manager = DownloadManagerHolder.manager ?: return
        scope.launch {
            val tasks = runCatching { manager.tasks.first() }.getOrDefault(emptyList())
            tasks.filter {
                it.status == DownloadTaskEntity.STATUS_PAUSED ||
                    it.status == DownloadTaskEntity.STATUS_FAILED
            }.forEach { manager.start(it.id) }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    // ---------- 视图小工具 ----------

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(40), 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun circleBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#CC1A1A1A"))
    }

    private fun roundedBackground() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor("#F2FFFFFF"))
        setStroke(dp(1), Color.parseColor("#22000000"))
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗下载",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("悬浮窗下载面板")
            .setContentText("正在显示下载进度")
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "floating_download"
        private const val NOTIFICATION_ID = 0x4A11
        const val ACTION_STOP = "com.jiexiy.app.action.STOP_FLOATING"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingDownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingDownloadService::class.java)) }
        }
    }
}
