package com.guanglu.caliper.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * 主界面：7 屏切换、权限申请与 SAF 调用。
 *
 * @author AI-Generated
 */
class MainActivity : ComponentActivity(), UiActions {

    private lateinit var container: AppContainer
    private lateinit var content: FrameLayout
    private var pendingChannel: Int? = null
    private var pendingBitmap: Bitmap? = null
    private var currentPage = "P2"
    private val navButtons = mutableMapOf<String, Button>()
    private val tabButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 采集页保持屏幕常亮。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = (application as CaliperApp).container
        container.uiEvent = { message -> runOnUiThread { toast(message) } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(10), bars.top + dp(6), dp(10), bars.bottom + dp(6))
            insets
        }
        root.addView(
            label("蓝牙卡尺测量数据助手 · v0.1.1", 12f, true),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        )

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7").forEach { id ->
            val button = compactButton(id) { setPage(id) }
            navButtons[id] = button
            nav.addView(button, evenParams())
        }
        root.addView(
            nav,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val tabs = horizontal()
        listOf("连接" to "P1", "检测" to "P2", "通道" to "P3", "分析" to "P5").forEach { (name, id) ->
            val button = compactButton(name) { setPage(id) }
            tabButtons[id] = button
            tabs.addView(button, evenParams())
        }
        root.addView(
            tabs,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        setContentView(root)

        setPage(currentPage)
        requestBlePermissions()
    }

    override fun onDestroy() {
        (content.getChildAt(0)?.tag as? Job)?.cancel()
        container.uiEvent = {}
        pendingBitmap = null
        super.onDestroy()
    }

    // ---- 7 屏切换 ----

    override fun setPage(pageId: String) {
        currentPage = pageId
        navButtons.forEach { (id, button) -> button.alpha = if (id == pageId) 1f else 0.55f }
        tabButtons.forEach { (id, button) -> button.alpha = if (id == pageId) 1f else 0.55f }
        (content.getChildAt(0)?.tag as? Job)?.cancel()
        content.removeAllViews()
        content.addView(Pages.build(this, pageId, container, this))
    }

    // ---- UiActions ----

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

    // ---- SAF 与权限 ----

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
        if (requestCode == REQ_PERMISSIONS && grantResults.any { it != 0 }) {
            toast("蓝牙权限被拒绝，可在系统设置里开启；Android 10/11 还需打开定位总开关")
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_EXPORT_CSV = 2001
        private const val REQ_IMPORT_CSV = 2002
        private const val REQ_EXPORT_PNG = 2003
        private const val REQ_DIAGNOSTICS = 2004
    }
}
