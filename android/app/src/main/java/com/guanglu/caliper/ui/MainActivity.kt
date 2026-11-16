package com.guanglu.caliper.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.CaliperApp
import com.guanglu.caliper.core.model.BUBBLE_BOTTOM_MARGIN_DP
import com.guanglu.caliper.core.model.BUBBLE_CORNER_RADIUS_DP
import com.guanglu.caliper.core.model.BUBBLE_MAX_LINES
import com.guanglu.caliper.core.model.BUBBLE_PADDING_H_DP
import com.guanglu.caliper.core.model.BUBBLE_PADDING_V_DP
import com.guanglu.caliper.core.model.BUBBLE_SIDE_MARGIN_DP
import com.guanglu.caliper.core.model.BUBBLE_TEXT_SIZE_SP
import com.guanglu.caliper.core.model.activityLaunchFailureMessage
import com.guanglu.caliper.core.model.bubbleDurationMs
import com.guanglu.caliper.core.model.bubbleFill
import com.guanglu.caliper.core.model.bubbleMaxWidthPx
import com.guanglu.caliper.core.model.bubbleToneOf
import com.guanglu.caliper.core.model.safResultMessage
import com.guanglu.caliper.core.usecase.ColoredSheetCodec
import com.guanglu.caliper.ui.pages.Pages
import com.guanglu.caliper.ui.pages.UiActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 主界面：蓝色标题栏、页面容器与底部四标签。
 *
 * @author AI-Generated
 */
class MainActivity : ComponentActivity(), UiActions {

    private lateinit var container: AppContainer
    private lateinit var content: FrameLayout
    private lateinit var appTitle: TextView
    private lateinit var appBadge: TextView
    private lateinit var tabBar: LinearLayout
    private var pendingChannel: Int? = null
    private var pendingBitmap: Bitmap? = null
    private var currentPage = "P2"
    private lateinit var bubbleHost: FrameLayout
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private var bubbleDismiss: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = (application as CaliperApp).container
        container.uiEvent = { message -> runOnUiThread { toast(message) } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Web.BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val appbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Web.PRIMARY)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { setPage("P2") }
        }
        appbar.addView(back, LinearLayout.LayoutParams(dp(26), dp(26)))
        appTitle = label("实时检测 · 记录", 16f, true, Color.WHITE)
        appbar.addView(
            appTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        )
        appBadge = webBadge("手动")
        appbar.addView(appBadge)
        root.addView(appbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(Web.APPBAR_H)))

        content = FrameLayout(this).apply {
            setBackgroundColor(Web.BG)
            setPadding(
                dp(Web.VIEWPORT_PAD),
                dp(Web.VIEWPORT_PAD),
                dp(Web.VIEWPORT_PAD),
                dp(Web.VIEWPORT_PAD)
            )
        }
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            View(this).apply { setBackgroundColor(Web.LINE) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        )
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Web.CARD)
        }
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(Web.TABBAR_H))
        )
        setContentView(root)
        setupBubbleHost()
        setPage(currentPage)
        requestBlePermissions()
    }

    /** 气泡浮层挂在页面之上但不拦截触摸。 */
    private fun setupBubbleHost() {
        bubbleHost = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
        }
        addContentView(
            bubbleHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onStart() {
        super.onStart()
        container.autoConnectKnown()?.let { reason ->
            container.diagnostics.log("AUTO_CONNECT_SKIP $reason")
        }
    }

    override fun onDestroy() {
        (content.getChildAt(0)?.tag as? Job)?.cancel()
        container.uiEvent = {}
        pendingBitmap = null
        bubbleDismiss?.let { bubbleHandler.removeCallbacks(it) }
        bubbleDismiss = null
        if (::bubbleHost.isInitialized) bubbleHost.removeAllViews()
        super.onDestroy()
    }

    override fun setPage(pageId: String) {
        currentPage = pageId
        (content.getChildAt(0)?.tag as? Job)?.cancel()
        content.removeAllViews()
        content.addView(Pages.build(this, pageId, container, this))
        appTitle.text = PAGE_TITLES[pageId] ?: "实时检测 · 记录"
        appBadge.text = if (pageId == "P2") "手动" else "7 通道"
        renderTabs()
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        listOf("连接" to "P1", "检测" to "P2", "通道" to "P3", "分析" to "P5").forEach { (name, id) ->
            tabBar.addView(webTab(name, id == currentPage) { setPage(id) })
        }
    }

    /** 轻量气泡：替换上一条消息，短停留、字号放大但宽度封顶，不拦截页面点击。 */
    override fun toast(message: String) {
        bubbleDismiss?.let { bubbleHandler.removeCallbacks(it) }
        bubbleHost.removeAllViews()
        val tone = bubbleToneOf(message)
        val bubble = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = BUBBLE_TEXT_SIZE_SP
            gravity = Gravity.CENTER
            maxLines = BUBBLE_MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = bubbleMaxWidthPx(
                resources.displayMetrics.widthPixels, dp(BUBBLE_SIDE_MARGIN_DP)
            )
            setPadding(
                dp(BUBBLE_PADDING_H_DP), dp(BUBBLE_PADDING_V_DP),
                dp(BUBBLE_PADDING_H_DP), dp(BUBBLE_PADDING_V_DP)
            )
            background = GradientDrawable().apply {
                setColor(bubbleFill(tone))
                cornerRadius = dp(BUBBLE_CORNER_RADIUS_DP).toFloat()
            }
            isClickable = false
            isFocusable = false
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(BUBBLE_BOTTOM_MARGIN_DP)
            leftMargin = dp(BUBBLE_SIDE_MARGIN_DP)
            rightMargin = dp(BUBBLE_SIDE_MARGIN_DP)
        }
        bubbleHost.addView(bubble, params)
        val dismiss = Runnable { bubbleHost.removeAllViews() }
        bubbleDismiss = dismiss
        bubbleHandler.postDelayed(dismiss, bubbleDurationMs(message.length))
    }

    override fun exportCsv(channelNo: Int?) {
        pendingChannel = channelNo
        createDocument("text/csv", "measurements.csv", REQ_EXPORT_CSV, "CSV 导出")
    }

    override fun exportColoredSheet() {
        createDocument(ColoredSheetCodec.MIME, "measurements.xlsx", REQ_EXPORT_SHEET, "彩色表格导出")
    }

    override fun importCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launchSafely(intent, REQ_IMPORT_CSV, "CSV 导入")
    }

    override fun exportDiagnostics() {
        createDocument("text/csv", "diagnostics.csv", REQ_DIAGNOSTICS, "诊断日志导出")
    }

    override fun exportPng(bitmap: Bitmap) {
        pendingBitmap = bitmap
        createDocument("image/png", "chart.png", REQ_EXPORT_PNG, "图片导出")
    }

    override fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        launchSafely(intent, null, "系统设置")
    }

    /** 系统应用缺失时给出提示并清理暂存，避免点击后无任何反应。 */
    private fun launchSafely(intent: Intent, requestCode: Int?, label: String) {
        try {
            if (requestCode == null) startActivity(intent) else startActivityForResult(intent, requestCode)
        } catch (error: ActivityNotFoundException) {
            container.diagnostics.log("ACTIVITY_NOT_FOUND $label ${error.javaClass.simpleName}")
            if (requestCode != null) clearPending(requestCode)
            toast(activityLaunchFailureMessage(label))
        }
    }

    private fun createDocument(mime: String, title: String, requestCode: Int, label: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, title)
        }
        launchSafely(intent, requestCode, label)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val label = when (requestCode) {
            REQ_EXPORT_CSV -> "CSV 导出"
            REQ_EXPORT_SHEET -> "彩色表格导出"
            REQ_IMPORT_CSV -> "CSV 导入"
            REQ_DIAGNOSTICS -> "诊断日志导出"
            REQ_EXPORT_PNG -> "图片导出"
            else -> return
        }
        val uri = data?.data
        val blocked = safResultMessage(resultCode == RESULT_OK, uri != null, label)
        if (blocked != null) {
            toast(blocked)
            clearPending(requestCode)
            return
        }
        if (uri == null) {
            toast("$label 失败：未取得文件位置，请重试")
            clearPending(requestCode)
            return
        }
        when (requestCode) {
            REQ_EXPORT_CSV -> container.exportCsv(uri, pendingChannel)
            REQ_EXPORT_SHEET -> container.exportColoredSheet(uri)
            REQ_IMPORT_CSV -> container.importCsv(uri)
            REQ_DIAGNOSTICS -> container.exportDiagnostics(uri)
            REQ_EXPORT_PNG -> {
                val bitmap = pendingBitmap
                if (bitmap == null) {
                    toast("图片导出失败：图表尚未就绪，请回到数据分析页重试")
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = runCatching { container.io.exportPng(uri, bitmap) }
                        runOnUiThread {
                            toast(
                                result.fold(
                                    onSuccess = { ok ->
                                        if (ok) "PNG 已保存" else "PNG 保存失败，请检查文件位置后重试"
                                    },
                                    onFailure = { error ->
                                        "图片导出失败：${error.javaClass.simpleName}，请重试"
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
        clearPending(requestCode)
    }

    /** 每次文件操作结束后清理暂存，避免下次导出复用上一次的通道或图表。 */
    private fun clearPending(requestCode: Int) {
        when (requestCode) {
            REQ_EXPORT_CSV -> pendingChannel = null
            REQ_EXPORT_PNG -> pendingBitmap = null
            REQ_EXPORT_SHEET -> Unit
        }
    }

    private fun requestBlePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(needed, REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == 0 }) {
                container.autoConnectKnown()?.let { reason ->
                    container.diagnostics.log("AUTO_CONNECT_SKIP $reason")
                }
            } else {
                toast("蓝牙权限被拒绝，可在系统设置里开启；Android 10/11 还需打开定位总开关")
            }
        }
    }

    companion object {
        private val PAGE_TITLES = mapOf(
            "P1" to "连接卡尺",
            "P2" to "实时检测 · 记录",
            "P3" to "多通道",
            "P4" to "表格模板",
            "P5" to "数据分析",
            "P6" to "导出到本地",
            "P7" to "设置"
        )
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_EXPORT_CSV = 2001
        private const val REQ_IMPORT_CSV = 2002
        private const val REQ_EXPORT_PNG = 2003
        private const val REQ_DIAGNOSTICS = 2004
        private const val REQ_EXPORT_SHEET = 2005
    }
}
