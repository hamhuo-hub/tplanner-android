package com.hamhuo.tplanner

import android.app.Application

/**
 * 诊断缓冲区必须在任何进程入口之前就绪。
 *
 * 后台同步由 WorkManager、开机广播和中继服务触发，这时通常没有 Activity：
 * 只靠 MainActivity 初始化会让"后台重试为什么失败"这一段完全没有记录。
 */
class TPlannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStore.init(this)
    }
}
