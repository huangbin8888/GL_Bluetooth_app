package com.guanglu.caliper.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.CaliperApp
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

        setPage(currentPage)
        requestBlePermissions()
    }

    override fun onStart() {
        super.onStart()
        container.autoConnectKnown()?.let { toast(it) }
    }

    override fun onDestroy() {
        (content.getChildAt(0)?.tag as? Job)?.cancel()
        container.uiEvent = {}
        pendingBitmap = null
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

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun exportCsv(channelNo: Int?) {
        pendingChannel = channelNo
        createDocument("text/csv", "measurements.csv", REQ_EXPORT_CSV)
    }

    override fun importCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT_CSV)
    }

    override fun exportDiagnostics() {
        createDocument("text/csv", "diagnostics.csv", REQ_DIAGNOSTICS)
    }

    override fun exportPng(bitmap: Bitmap) {
        pendingBitmap = bitmap
        createDocument("image/png", "chart.png", REQ_EXPORT_PNG)
    }

    override fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun createDocument(mime: String, title: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, title)
        }
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_CSV -> container.exportCsv(uri, pendingChannel)
            REQ_IMPORT_CSV -> container.importCsv(uri)
            REQ_DIAGNOSTICS -> container.exportDiagnostics(uri)
            REQ_EXPORT_PNG -> pendingBitmap?.let { bitmap ->
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = container.io.exportPng(uri, bitmap)
                    runOnUiThread { toast(if (ok) "PNG 已保存" else "PNG 保存失败") }
                }
            }
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
                container.autoConnectKnown()?.let { toast(it) }
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
    }
}
