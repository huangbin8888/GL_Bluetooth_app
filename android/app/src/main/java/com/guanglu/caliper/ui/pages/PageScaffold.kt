package com.guanglu.caliper.ui.pages

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView

/** 页面根滚动容器。 */
internal fun screen(context: Context): ScrollView {
    val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    return ScrollView(context).apply { addView(column) }
}

/** 页面根滚动容器的内容列。 */
internal fun ScrollView.col(): LinearLayout = getChildAt(0) as LinearLayout
