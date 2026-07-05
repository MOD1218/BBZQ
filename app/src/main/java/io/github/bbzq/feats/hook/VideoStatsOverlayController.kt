package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

internal class VideoStatsOverlayController(
    private val application: Application,
    private val resolveIdentity: () -> UserWatermarkIdentity,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var topActivity = WeakReference<Activity>(null)
    private var activeStatsContent = WeakReference<LinearLayout>(null)
    private val pendingAttach = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    @Volatile private var latestStats: VideoStreamStats? = null
    private var groupId = 0

    fun install() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                topActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (topActivity.get() === activity) topActivity.clear()
            }
        })
    }

    fun update(stats: VideoStreamStats) {
        latestStats = stats
        mainHandler.post {
            val activity = topActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                ?: return@post
            ensurePlayerComponent(activity)
            activeStatsContent.get()?.let { renderStats(it, stats) }
        }
    }

    private fun ensurePlayerComponent(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(STATS_TAG) != null) return
        if (!pendingAttach.add(activity)) return
        resolveGroupId(decor, activity)
        attachPlayerComponent(activity, decor, ATTACH_RETRY_COUNT)
    }

    private fun resolveGroupId(decor: ViewGroup, activity: Activity) {
        if (groupId == 0) {
            groupId = activity.resources.getIdentifier("right_bottom_widget_group", "id", activity.packageName)
        }
    }

    private fun attachPlayerComponent(activity: Activity, decor: ViewGroup, attemptsLeft: Int) {
        if (activity.isFinishing || activity.isDestroyed) {
            pendingAttach.remove(activity)
            return
        }
        if (groupId == 0) {
            pendingAttach.remove(activity)
            return
        }
        val group = decor.findViewById<ViewGroup>(groupId)
        if (group == null) {
            if (attemptsLeft > 0) {
                mainHandler.postDelayed(
                    { attachPlayerComponent(activity, decor, attemptsLeft - 1) },
                    ATTACH_RETRY_DELAY_MS,
                )
            } else {
                pendingAttach.remove(activity)
            }
            return
        }
        pendingAttach.remove(activity)
        val density = activity.resources.displayMetrics.density
        StatsIconView(activity).apply {
            tag = STATS_TAG
            setOnClickListener { showFirstWarning(activity) }
            contentDescription = "视频统计信息"
            layoutParams = LinearLayout.LayoutParams(
                (44f * density).toInt(),
                (44f * density).toInt(),
            )
            group.addView(this)
        }
    }

    private fun showFirstWarning(activity: Activity) {
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("敏感信息警告（1/2）")
                .setMessage("视频统计信息可能用于识别客户端行为。请勿截图传播、公开展示或据此骚扰内容创作者。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续") { _, _ -> showDisclaimer(activity) }
                .show()
        }.onFailure { reportFailure("failed to show first warning", it) }
    }

    private fun showDisclaimer(activity: Activity) {
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("免责声明（2/2）")
                .setMessage(
                    "此页面仅展示播放器响应中的流元数据，不代表网络瞬时速度，也不能恢复服务端未下发的源文件。" +
                        "页面带有当前账号水印；继续即表示仅供个人诊断使用并自行承担传播风险。",
                )
                .setNegativeButton("不同意", null)
                .setPositiveButton("同意并查看") { _, _ -> showStats(activity) }
                .show()
        }.onFailure { reportFailure("failed to show disclaimer", it) }
    }

    private fun showStats(activity: Activity) {
        val stats = latestStats ?: return
        runCatching {
            val identity = resolveIdentity()
            val watermark = listOfNotNull(
                identity.userName.takeIf { it.isNotBlank() },
                identity.uid.takeIf { it.isNotBlank() }?.let { "UID $it" },
            ).joinToString(" · ").ifBlank { "未登录用户 · UID 未知" }
            val statsContent = createStatsContent(activity, stats)
            activeStatsContent = WeakReference(statsContent)
            val content = FrameLayout(activity).apply {
                setPadding(dp(24), dp(18), dp(24), dp(12))
                setBackgroundColor(Color.rgb(46, 46, 46))
                addView(
                    ScrollView(activity).apply {
                        isFillViewport = true
                        addView(statsContent)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    RepeatingWatermarkView(activity, watermark),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            val dialog = AlertDialog.Builder(activity)
                .setView(content)
                .setPositiveButton("确定", null)
                .show()
            dialog.setOnDismissListener { activeStatsContent.clear() }
            dialog.window?.apply {
                setBackgroundDrawable(
                    GradientDrawable().apply {
                        setColor(Color.rgb(46, 46, 46))
                        cornerRadius = dp(4).toFloat()
                    },
                )
                val metrics = activity.resources.displayMetrics
                setLayout((metrics.widthPixels * 0.90f).toInt(), (metrics.heightPixels * 0.62f).toInt())
            }
        }.onFailure { reportFailure("failed to show statistics", it) }
    }

    private fun createStatsContent(activity: Activity, stats: VideoStreamStats): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            renderStats(this, stats)
        }

    private fun renderStats(content: LinearLayout, stats: VideoStreamStats) {
        val activity = content.context as? Activity ?: return
        content.removeAllViews()
        content.addView(sectionTitle(activity, "视频信息", 22f))
        content.addView(sectionTitle(activity, "视频", 19f))
        content.addView(statLine(activity, "请求画质", "QN 127（最高档）"))
        content.addView(statLine(activity, "实际分辨率", resolution(stats)))
        content.addView(statLine(activity, "流元数据码率", bitrate(stats.bandwidth)))
        content.addView(statLine(activity, "实际画质 QN", stats.quality.toString()))
        content.addView(statLine(activity, "编码 ID", codecLabel(stats.codecId)))
        if (stats.frameRate.isNotBlank()) content.addView(statLine(activity, "帧率", stats.frameRate))

        content.addView(sectionTitle(activity, "音频", 19f))
        if (stats.audioBandwidth > 0 || stats.audioCodecId > 0) {
            content.addView(statLine(activity, "音频码率", bitrate(stats.audioBandwidth)))
            content.addView(statLine(activity, "音频编码", codecLabel(stats.audioCodecId)))
            if (stats.audioSampleRate > 0) {
                val srLabel = if (stats.audioSampleRate >= 1000) {
                    "%.1f kHz".format(stats.audioSampleRate / 1000.0)
                } else "${stats.audioSampleRate} Hz"
                content.addView(statLine(activity, "采样率", srLabel))
            }
            if (stats.audioChannels > 0) {
                val chLabel = when (stats.audioChannels) {
                    1 -> "单声道"
                    2 -> "立体声"
                    6 -> "5.1 环绕"
                    8 -> "7.1 环绕"
                    else -> "${stats.audioChannels} 声道"
                }
                content.addView(statLine(activity, "声道", chLabel))
            }
        } else {
            content.addView(statLine(activity, "音频信息", "响应未提供"))
        }
    }

    private fun codecLabel(id: Long): String = when (id.toInt()) {
        7 -> "AVC / H.264"
        12 -> "HEVC / H.265"
        13 -> "AV1"
        0, 1 -> "AAC"
        2 -> "MP3"
        3 -> "FLAC"
        4 -> "Opus"
        5 -> "AC-3 / Dolby Digital"
        6 -> "E-AC-3 / Dolby Digital Plus"
        else -> "ID $id"
    }

    private fun statLine(activity: Activity, label: String, value: String) = TextView(activity).apply {
        text = "$label：$value"
        textSize = 15f
        setTextColor(Color.rgb(238, 238, 238))
        setPadding(0, dp(9), 0, dp(9))
    }

    private fun sectionTitle(activity: Activity, title: String, size: Float) = TextView(activity).apply {
        text = title
        textSize = size
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun resolution(stats: VideoStreamStats): String = when {
        stats.width > 0 && stats.height > 0 -> "${stats.width} × ${stats.height}"
        else -> "响应未提供"
    }

    private fun bitrate(value: Long): String = when {
        value <= 0 -> "响应未提供"
        value >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps (%d kbps)", value / 1_000_000.0, value / 1000)
        else -> "${value / 1000} kbps"
    }

    private fun dp(value: Int): Int = (value * application.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val STATS_TAG = "bbzq_video_stats_entry"
        const val ATTACH_RETRY_COUNT = 12
        const val ATTACH_RETRY_DELAY_MS = 400L
    }
}

internal data class UserWatermarkIdentity(val uid: String, val userName: String)

/** 自定义统计图标 View — 画一个柱状图 + 折线 */
private class StatsIconView(context: android.content.Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 100, 130)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val size = minOf(w, h) * 0.45f
        val left = cx - size
        val top = cy - size
        val barW = size * 0.18f
        val gap = size * 0.14f
        val baseY = cy + size * 0.4f

        val bars = floatArrayOf(0.3f, 0.7f, 0.5f)
        for (i in bars.indices) {
            val bx = left + gap + i * (barW + gap)
            val bh = size * bars[i]
            canvas.drawRect(bx, baseY - bh, bx + barW, baseY, barPaint)
        }

        val path = Path()
        for (i in bars.indices) {
            val px = left + gap + i * (barW + gap) + barW / 2f
            val py = baseY - size * bars[i]
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)

        for (i in bars.indices) {
            val px = left + gap + i * (barW + gap) + barW / 2f
            val py = baseY - size * bars[i]
            canvas.drawCircle(px, py, 2.5f * density, dotPaint)
        }
    }
}

private class RepeatingWatermarkView(
    context: android.content.Context,
    private val watermark: String,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 60, 90)
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val xStep = 220f * resources.displayMetrics.density
        val yStep = 88f * resources.displayMetrics.density
        var y = -height.toFloat()
        while (y < height * 2f) {
            var x = -width.toFloat()
            while (x < width * 2f) {
                canvas.save()
                canvas.rotate(-24f, x, y)
                canvas.drawText(watermark, x, y, paint)
                canvas.restore()
                x += xStep
            }
            y += yStep
        }
    }
}
