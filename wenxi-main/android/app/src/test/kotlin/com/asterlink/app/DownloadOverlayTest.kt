package com.asterlink.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class DownloadOverlayTest {
    private lateinit var overlay: DownloadOverlay
    private lateinit var activity: Activity
    private var allowed = true
    private val states = mutableListOf<Map<String, Any>>()
    private val actions = mutableListOf<Pair<String, String?>>()
    private val errors = mutableListOf<Throwable>()
    private fun value(done: Boolean = false) = mapOf(
        "runId" to "task-1",
        "count" to 1, "active" to if (done) 0 else 1, "completed" to if (done) 1 else 0,
        "speed" to 1024, "progress" to if (done) 100 else 40,
        "canPause" to !done, "canResume" to false, "status" to if (done) "全部完成" else "正在下载",
        "tasks" to listOf(mapOf("id" to "task-1", "name" to "测试文件.zip", "action" to if (done) "" else "pause", "progress" to 40))
    )
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("download_overlay", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        overlay = DownloadOverlay(app, { activity }, { name, id, complete -> actions.add(name to id); complete(true) },
            { states.add(it) }, { _, error -> errors.add(error) }, { allowed })
    }
    @After fun cleanup() { overlay.close(); activity.finish() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(label: String) = views(overlay.contentView!!).filterIsInstance<TextView>().first { it.text == label }
    private fun control(description: String) = views(overlay.contentView!!).first { it.contentDescription == description }

    private fun capturePreview(view: View, name: String) {
        val directory = System.getenv("TRANSFER_UI_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = java.io.File(directory, "$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun doesNotOpenUntilExplicitRequestAndCloseDoesNotPauseDownloads() {
        overlay.update(value())
        assertFalse(overlay.visible)
        overlay.show(value())
        assertTrue(overlay.visible)
        control("关闭悬浮窗，继续传输").performClick()
        assertFalse(overlay.visible)
        assertTrue(actions.isEmpty())
        assertTrue(errors.isEmpty())
    }
    @Test fun permissionIsRequestedAndDeniedPermissionDoesNotCreateWindow() {
        allowed = false
        assertEquals(true, overlay.show(value())["pendingPermission"])
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(activity).nextStartedActivity.action)
        overlay.resumed()
        assertFalse(overlay.visible)
        assertFalse(overlay.pendingPermission)
    }
    @Test fun grantedPermissionReturnsToTheRequestedWindowAndRevocationClosesIt() {
        allowed = false
        overlay.show(value())
        allowed = true
        overlay.resumed()
        assertTrue(overlay.visible)
        allowed = false
        overlay.update(value())
        assertFalse(overlay.visible)
    }
    @Test fun canCollapseExpandAndRetainCompletedProgress() {
        overlay.show(value())
        control("收起为悬浮球").performClick()
        assertTrue(overlay.collapsed)
        overlay.update(value(done = true))
        assertTrue(overlay.visible)
        assertTrue(overlay.contentView!!.contentDescription.toString().contains("100%"))
        overlay.contentView!!.performClick()
        assertFalse(overlay.collapsed)
        assertTrue(views(overlay.contentView!!).filterIsInstance<TextView>().any { it.text.contains("全部完成") })
    }
    @Test fun taskAndTitleControlsReachTheExistingCoordinator() {
        overlay.show(value())
        button("暂停").performClick()
        control("打开传输管理").performClick()
        assertEquals(listOf("pause" to "task-1", "openDownloads" to null), actions)
    }

    @Test fun paletteUpdatesKeepWindowBoundsTasksAndCollapsedState() {
        overlay.show(value())
        val bounds = overlay.currentBounds
        val palette = mapOf(
            "accent" to 0xFF087F62L, "onAccent" to 0xFFFFFFFFL,
            "companion" to 0xFF067C8CL, "surface" to 0xFFFDFEFEL,
            "background" to 0xFFF0F7F4L, "foreground" to 0xFF17191DL,
            "muted" to 0xFF656975L, "border" to 0xFFDEE8E3L,
            "softAccent" to 0xFFE5F4EEL, "glassStyle" to "solid"
        )
        overlay.update(value() + mapOf("appearance" to palette))
        assertEquals(bounds, overlay.currentBounds)
        assertEquals(0xFF087F62.toInt(), button("暂停").currentTextColor)
        assertEquals(0xFFFFFFFF.toInt(), (control("收起为悬浮球") as TextView).currentTextColor)
        assertTrue(actions.isEmpty())
        control("收起为悬浮球").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(overlay.contentView!!.contentDescription.toString().contains("已贴边隐藏"))
        val ballBounds = overlay.currentBounds
        overlay.update(value() + mapOf("dark" to true, "appearance" to (palette + mapOf(
            "accent" to 0xFF68DCB5L, "onAccent" to 0xFF101418L,
            "surface" to 0xFF141817L
        ))))
        assertTrue(overlay.collapsed)
        assertEquals(ballBounds, overlay.currentBounds)
        assertTrue(overlay.contentView!!.contentDescription.toString().contains("40%"))
        assertTrue(actions.isEmpty())
        overlay.contentView!!.performClick()
        assertEquals(0xFF101418.toInt(), (control("收起为悬浮球") as TextView).currentTextColor)
        assertTrue(errors.isEmpty())
    }

    @Test fun invalidThemeValuesFallBackToReadableDefaults() {
        overlay.show(value() + mapOf("appearance" to mapOf("accent" to 1, "surface" to "invalid")))
        assertEquals(0xFF0068E0.toInt(), button("暂停").currentTextColor)
        assertTrue(overlay.visible)
        assertTrue(errors.isEmpty())
    }
    @Test fun dragAndResizeAreClampedToTheScreen() {
        overlay.show(value())
        val initial = overlay.currentBounds
        val handle = views(overlay.contentView!!).first { it.contentDescription == "拖动调整小窗大小" }
        for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 0f, 0f), Triple(MotionEvent.ACTION_MOVE, -1000f, -1000f), Triple(MotionEvent.ACTION_UP, -1000f, -1000f))) {
            MotionEvent.obtain(0, 10, action, x, y, 0).also { handle.dispatchTouchEvent(it); it.recycle() }
        }
        assertTrue(overlay.currentBounds.width > 0)
        assertTrue(overlay.currentBounds.height > 0)
        assertTrue(overlay.currentBounds.width <= initial.width)
        assertTrue(overlay.currentBounds.x >= 0)
        assertTrue(errors.isEmpty())
    }
    @Test fun boundsRemainVisibleOnSmallScreensAndAfterRotation() {
        val original = OverlayBounds(950, 700, 500, 650)
        assertEquals(OverlayBounds(0, 0, 240, 180), original.fit(240, 180, 280, 230))
        val rotated = original.fit(780, 360, 280, 230)
        assertTrue(rotated.x + rotated.width <= 780)
        assertTrue(rotated.y + rotated.height <= 360)
    }

    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun collapseRemainsVisibleAndSeparateFromResizeAtMinimumSize() {
        overlay.show(value())
        val handle = control("拖动调整小窗大小")
        for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 0f, 0f), Triple(MotionEvent.ACTION_MOVE, -1000f, -1000f), Triple(MotionEvent.ACTION_UP, -1000f, -1000f))) {
            MotionEvent.obtain(0, 10, action, x, y, 0).also { handle.dispatchTouchEvent(it); it.recycle() }
        }
        assertEquals(200, overlay.currentBounds.width)
        assertEquals(180, overlay.currentBounds.height)
        assertBottomControlsFit()
        control("收起为悬浮球").performClick()
        assertTrue(overlay.collapsed)
    }

    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeTextAndThemeChangePreserveTheCollapseControl() {
        RuntimeEnvironment.setFontScale(1.5f)
        overlay.close()
        RuntimeEnvironment.getApplication().getSharedPreferences("download_overlay", 0).edit()
            .putInt("width", 200).putInt("height", 180).commit()
        val app = RuntimeEnvironment.getApplication()
        overlay = DownloadOverlay(app, { activity }, { _, _, done -> done(true) }, {}, { _, error -> errors.add(error) }, { allowed })
        overlay.show(value())
        assertBottomControlsFit()
        overlay.update(value() + ("dark" to true))
        assertBottomControlsFit()
        control("收起为悬浮球").performClick()
        overlay.update(value(done = true) + ("dark" to true))
        assertTrue(overlay.collapsed)
        assertTrue(overlay.visible)
        assertTrue(errors.isEmpty())
    }

    private fun assertBottomControlsFit() {
        val root = overlay.contentView!! as ViewGroup
        val bounds = overlay.currentBounds
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, bounds.width, bounds.height)
        }
        val collapse = control("收起为悬浮球") as TextView
        val close = control("关闭悬浮窗，继续传输") as TextView
        val handle = control("拖动调整小窗大小")
        fun area(view: View): Rect = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
        val buttonArea = area(collapse)
        val closeArea = area(close)
        val handleArea = area(handle)
        assertTrue(Rect(0, 0, root.width, root.height).contains(buttonArea))
        assertTrue(Rect(0, 0, root.width, root.height).contains(closeArea))
        assertTrue(Rect(0, 0, root.width, root.height).contains(handleArea))
        assertFalse(Rect.intersects(buttonArea, handleArea))
        assertFalse(Rect.intersects(buttonArea, closeArea))
        assertFalse(Rect.intersects(closeArea, handleArea))
        assertEquals(36, collapse.height)
        assertEquals(32, handle.width)
        assertEquals(32, handle.height)
        assertEquals("关闭", close.text.toString())
        assertEquals(0, close.layout.getEllipsisCount(0))
        assertEquals(0, collapse.layout.getEllipsisCount(0))
    }

    @Test @Config(qualifiers = "w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactWindowShowsSixCompleteTasksAndSmallWindowShowsFour() {
        val names = listOf("周末旅行 · 精选片段.mp4", "设计资料备份.zip", "海边日落.mp4", "相机原图合集.zip",
            "项目文档.pdf", "音乐收藏.flac", "安装包备份.apk", "旅行攻略.pdf")
        val data = value() + mapOf("direction" to "mixed", "count" to 8, "active" to 8,
            "downloadCount" to 4, "uploadCount" to 4, "downloadActive" to 4, "uploadActive" to 4,
            "downloadSpeed" to 5 * 1024 * 1024, "uploadSpeed" to 2 * 1024 * 1024, "status" to "正在传输",
            "tasks" to (1..8).map { index ->
                val uploading = index % 2 == 0
                mapOf("id" to "$index", "name" to names[index - 1], "action" to if (uploading) "openUploads" else "pause",
                    "actionLabel" to if (uploading) "管理" else "暂停", "direction" to if (uploading) "upload" else "download",
                    "status" to "running", "label" to if (uploading) "正在上传" else "正在下载", "progress" to 40,
                    "downloaded" to 80 * 1024 * 1024, "total" to 200 * 1024 * 1024, "speed" to if (uploading) 512 * 1024 else 1280 * 1024)
            })
        overlay.show(data)
        fun assertVisibleTasks(height: Int, minimum: Int) {
            val root = overlay.contentView!! as ViewGroup
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, height)
            }
            val scroll = views(root).first { it.tag == "download-task-list" }
            val rows = views(scroll).filter { it.tag == "download-task-row" }
            assertTrue("Task list height: ${scroll.height}; row bounds: ${rows.map { it.top to it.bottom }}",
                rows.count { it.bottom <= scroll.height } >= minimum)
            capturePreview(root, "floating-$height")
        }
        assertVisibleTasks(480, 6)
        assertVisibleTasks(360, 4)
        val root = overlay.contentView!! as ViewGroup
        val title = views(root).first { it.tag == "download-overlay-title" }
        val titleBounds = Rect(0, 0, title.width, title.height).also { root.offsetDescendantRectToMyCoords(title, it) }
        assertEquals(root.width / 2, titleBounds.centerX())
        assertFalse(views(root).any { it.contentDescription == "更多下载操作" })
        val close = control("关闭悬浮窗，继续传输")
        val closeBounds = Rect(0, 0, close.width, close.height).also { root.offsetDescendantRectToMyCoords(close, it) }
        assertTrue(closeBounds.top > root.height / 2)
        overlay.update(data + ("dark" to true))
        val darkRoot = overlay.contentView!!
        repeat(2) {
            darkRoot.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            darkRoot.layout(0, 0, 320, 480)
        }
        capturePreview(darkRoot, "floating-dark")
    }

    @Test @Config(qualifiers = "w400dp-h800dp-mdpi")
    fun widthAndHeightCanBeAdjustedIndependentlyAndSaved() {
        overlay.show(value())
        fun dragBy(description: String, dx: Float, dy: Float) {
            val target = control(description)
            for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 0f, 0f), Triple(MotionEvent.ACTION_MOVE, dx, dy), Triple(MotionEvent.ACTION_UP, dx, dy))) {
                MotionEvent.obtain(0, 10, action, x, y, 0).also { target.dispatchTouchEvent(it); it.recycle() }
            }
        }
        val initial = overlay.currentBounds
        dragBy("左右拖动调整小窗宽度", -90f, -200f)
        assertEquals(230, overlay.currentBounds.width)
        assertEquals(initial.height, overlay.currentBounds.height)
        dragBy("上下拖动调整小窗高度", 100f, -270f)
        assertEquals(230, overlay.currentBounds.width)
        assertEquals(210, overlay.currentBounds.height)
        dragBy("拖动调整小窗大小", -200f, -200f)
        assertEquals(200, overlay.currentBounds.width)
        assertEquals(180, overlay.currentBounds.height)
        dragBy("拖动调整小窗大小", 100f, 120f)
        assertEquals(300, overlay.currentBounds.width)
        assertEquals(300, overlay.currentBounds.height)
        val saved = overlay.currentBounds
        overlay.close()
        val app = RuntimeEnvironment.getApplication()
        overlay = DownloadOverlay(app, { activity }, { _, _, done -> done(true) }, {}, { _, error -> errors.add(error) }, { allowed })
        overlay.show(value())
        assertEquals(saved, overlay.currentBounds)
        assertTrue(errors.isEmpty())
    }

    @Test fun collapsedBallReplacesFailedStateWithTheLatestCompletedSnapshot() {
        overlay.show(value() + mapOf("active" to 0, "failed" to 1, "status" to "下载失败"))
        control("收起为悬浮球").performClick()
        assertTrue(overlay.contentView!!.contentDescription.contains("下载失败"))
        overlay.update(value())
        assertTrue(overlay.contentView!!.contentDescription.contains("正在下载"))
        overlay.update(value(done = true))
        assertTrue(overlay.contentView!!.contentDescription.contains("全部完成"))
        assertTrue(overlay.contentView!!.contentDescription.contains("100%"))
        assertFalse(overlay.contentView!!.contentDescription.contains("失败"))
    }

    private fun ballTouch(action: Int, x: Float = 20f, y: Float = 30f) {
        MotionEvent.obtain(0, 10, action, x, y, 0).also {
            overlay.contentView!!.dispatchTouchEvent(it); it.recycle()
        }
    }

    private fun captureBallPreview(name: String) {
        val ball = overlay.contentView!!
        val bounds = overlay.currentBounds
        ball.measure(View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY))
        ball.layout(0, 0, bounds.width, bounds.height)
        capturePreview(ball, name)
    }

    @Test @Config(qualifiers = "w400dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun idleBallDocksAfterThreeSecondsDespiteProgressRefreshAndClickReopens() {
        overlay.show(value())
        val collapsedAt = SystemClock.uptimeMillis()
        control("收起为悬浮球").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
        assertEquals(76, overlay.currentBounds.width)
        overlay.update(value() + ("progress" to 70))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((collapsedAt + 2999 - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        assertEquals(76, overlay.currentBounds.width)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(24, overlay.currentBounds.width)
        assertEquals(0, overlay.currentBounds.x)
        assertTrue(overlay.contentView!!.contentDescription.contains("已贴边隐藏"))
        captureBallPreview("ball-hidden-left")
        overlay.update(value(done = true))
        assertTrue(overlay.contentView!!.contentDescription.contains("100%"))
        assertEquals(24, overlay.currentBounds.width)
        ballTouch(MotionEvent.ACTION_DOWN)
        assertEquals(76, overlay.currentBounds.width)
        ballTouch(MotionEvent.ACTION_UP)
        assertFalse(overlay.collapsed)
        assertTrue(overlay.visible)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertFalse(overlay.collapsed)
        assertTrue(errors.isEmpty())
    }

    @Test @Config(qualifiers = "w400dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun draggingCancelsDockTimerAndHidesOnNearestEdgeAfterRelease() {
        overlay.show(value())
        control("收起为悬浮球").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ballTouch(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(76, overlay.currentBounds.width)
        ballTouch(MotionEvent.ACTION_MOVE, 370f)
        ballTouch(MotionEvent.ACTION_UP, 370f)
        assertTrue(overlay.collapsed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2999))
        assertEquals(76, overlay.currentBounds.width)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(24, overlay.currentBounds.width)
        assertEquals(376, overlay.currentBounds.x)
        captureBallPreview("ball-hidden-right")
        overlay.update(value() + ("dark" to true))
        assertEquals(24, overlay.currentBounds.width)
        assertEquals(376, overlay.currentBounds.x)
        overlay.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertFalse(overlay.visible)
        assertTrue(actions.isEmpty())
        assertTrue(errors.isEmpty())
    }

    private fun ballField(name: String): Any? = overlay.contentView!!.let { ball ->
        ball.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(ball)
    }

    @Test fun ballAnimatesRealProgressAndStopsWhenPausedOrDetached() {
        overlay.show(value())
        control("收起为悬浮球").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("The ball must be attached before animating", overlay.contentView!!.isAttachedToWindow)
        assertTrue("The ball must be shown before animating", overlay.contentView!!.isShown)
        overlay.update(value() + ("progress" to 70))
        assertTrue((ballField("animator") as ValueAnimator).isStarted)
        (ballField("animator") as ValueAnimator).currentPlayTime = 100L
        val progress = ballField("displayedProgress") as Float
        assertTrue("Expected an intermediate progress, actual: $progress, duration: ${(ballField("animator") as ValueAnimator).duration}", progress > 40f && progress < 70f)
        overlay.update(value() + mapOf("progress" to 70, "active" to 0, "speed" to 0))
        assertNull(ballField("animator"))
        assertEquals(70f, ballField("displayedProgress") as Float, 0f)
        overlay.update(value() + ("progress" to 80))
        val animator = ballField("animator") as ValueAnimator
        overlay.close()
        assertFalse(animator.isStarted)
        assertTrue(errors.isEmpty())
    }

    @Test fun completionFeedbackRunsOnceAndNewBatchDoesNotInheritIt() {
        overlay.show(value())
        control("收起为悬浮球").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        overlay.update(value(done = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180))
        assertTrue((ballField("completionFeedback") as Float) > 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse((ballField("animator") as ValueAnimator).isStarted)
        overlay.update(value(done = true))
        assertFalse((ballField("animator") as ValueAnimator).isStarted)
        overlay.update(value() + mapOf("runId" to "task-2", "progress" to 12))
        assertEquals(12f, ballField("displayedProgress") as Float, 0f)
        assertEquals(0f, ballField("completionFeedback") as Float, 0f)
        assertTrue(overlay.contentView!!.contentDescription.contains("12%"))
        assertTrue(errors.isEmpty())
    }

    private fun uploading() = value() + mapOf(
        "direction" to "upload", "runId" to "upload:task-1", "status" to "正在上传",
        "uploadCount" to 1, "downloadCount" to 0, "uploadActive" to 1, "downloadActive" to 0,
        "tasks" to listOf(mapOf("id" to "upload:task-1", "taskId" to "task-1", "name" to "上传文件.zip",
            "direction" to "upload", "status" to "running", "label" to "正在上传",
            "action" to "openUploads", "actionLabel" to "管理", "progress" to 40))
    )

    @Test fun uploadRowsOpenTheUploadTabAndNeverOfferDownloadPause() {
        overlay.show(uploading())
        assertTrue(views(overlay.contentView!!).filterIsInstance<TextView>().any { it.text == "上传文件.zip" })
        assertTrue(views(overlay.contentView!!).any { it.contentDescription == "上传" })
        assertFalse(views(overlay.contentView!!).filterIsInstance<TextView>().any { it.text == "暂停" })
        button("管理").performClick()
        assertEquals(listOf("openUploads" to "task-1"), actions)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("com.asterlink.app.OPEN_TRANSFERS", intent.action)
        assertEquals("uploads", intent.getStringExtra("transferTab"))
        control("收起为悬浮球").performClick()
        assertTrue(overlay.contentView!!.contentDescription.contains("正在上传"))
        assertTrue(overlay.contentView!!.contentDescription.contains("40%"))
    }

    @Test fun mixedTransferRowsUseSeparateIdsAndBallReportsBothDirections() {
        val up = uploading()
        val uploadRow = (up["tasks"] as List<*>).single()
        val downRow = mapOf("id" to "download:task-1", "taskId" to "task-1", "name" to "下载文件.zip",
            "direction" to "download", "action" to "pause", "actionLabel" to "暂停", "progress" to 25)
        overlay.show(up + mapOf("direction" to "mixed", "count" to 2, "active" to 2,
            "downloadActive" to 1, "downloadCount" to 1, "status" to "正在传输",
            "downloadSpeed" to 2048, "uploadSpeed" to 1024, "tasks" to listOf(uploadRow, downRow)))
        assertEquals(2, views(overlay.contentView!!).count { it.tag == "download-task-row" })
        button("暂停").performClick()
        assertEquals(listOf("pause" to "task-1"), actions)
        control("收起为悬浮球").performClick()
        val description = overlay.contentView!!.contentDescription.toString()
        assertTrue(description.contains("下载 2.0 KB/s"))
        assertTrue(description.contains("上传 1.0 KB/s"))
        assertTrue(errors.isEmpty())
    }
}
