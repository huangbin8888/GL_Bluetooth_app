package com.guanglu.caliper.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.CaliperApp
import com.guanglu.caliper.ui.pages.Pages
import com.guanglu.caliper.ui.pages.UiActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), UiActions {

    private lateinit var container: AppContainer
    private lateinit var content: FrameLayout
    private var pendingChannel: Int? = null
    private var pendingBitmap: Bitmap? = null
    private var currentPage = "P2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A1：采集页屏幕常亮，息屏可能触发省电断连，卡尺无历史数据可补。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = (application as CaliperApp).container
        container.uiEvent = { message -> runOnUiThread { toast(message) } }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            label("蓝牙卡尺测量数据助手 · 原生工程骨架 v0.1.0-m0", 14f, true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val navScroll = HorizontalScrollView(this).apply { addView(nav) }
        listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7").forEach { id ->
            nav.addView(actionButton(id) { setPage(id) })
        }
        root.addView(navScroll)

        content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val tabs = horizontal()
        listOf("连接" to "P1", "检测" to "P2", "通道" to "P3", "分析" to "P5").forEach { (name, id) ->
            tabs.addView(
                actionButton(name) { setPage(id) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        root.addView(tabs)
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
