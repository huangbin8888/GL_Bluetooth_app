package com.guanglu.caliper

import android.app.Application

/**
 * 应用入口，持有 AppContainer。
 *
 * @author AI-Generated
 */
class CaliperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        /** 页面通过 (application as CaliperApp).container 取依赖。 */
        fun from(app: Application): AppContainer = (app as CaliperApp).container
    }
}
