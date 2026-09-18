package com.hamhuo.tplanner

import android.app.Application

/**
 * 诊断缓冲区必须在任何进程入口之前就绪。
 *
 * 手表同步多数由 JobScheduler、开机广播和表盘触发，这时没有 Activity：
 * 只靠 MainActivity 初始化会让"后台那几次同步到底断在哪一跳"完全没有记录。
 */
class TPlannerWearApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStore.init(this)
    }
}
