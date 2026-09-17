package com.hamhuo.tplanner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry

/**
 * The single application frame: one card, two surfaces.
 *
 * 主界面底部不再有任何导航条——底栏那条悬浮岛已删除。切换 Inbox / 今天、翻日期、
 * 进设置全部收在右上角那一个控制器里（[DayControlDock]）。于是这一屏真正常驻的只有
 * 三样东西：Timeline、底部的 Plan 输入条、右上角控制点。
 *
 * 折叠屏与外接大屏暂时复用同一套排版；更通用的自适应方案以后单独设计。
 */
@Composable
internal fun MainLayout(
    showInbox: Boolean,
    dayCard: @Composable () -> Unit,
    taskCard: @Composable () -> Unit,
) {
    // 系统栏只在这里让位一次：键盘的内边距归 Plan 面板自己处理，避免叠加。
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(TPlannerGeometry.RadiusAppFrameDp.dp),
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            val surfaceStateHolder = rememberSaveableStateHolder()
            surfaceStateHolder.SaveableStateProvider(showInbox) {
                if (showInbox) taskCard() else dayCard()
            }
        }
    }
}
