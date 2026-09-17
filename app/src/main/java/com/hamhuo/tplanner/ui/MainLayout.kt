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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry

/**
 * The single application frame.
 *
 * 原来按 `screenWidthDp < 840` 分成"手机"和"宽屏两栏"两套排版，现在只有这一套：
 * 「今天」= 时间轴 + Note，「Inbox」= 任务列表。折叠屏与宽屏暂时复用同一排版，
 * 更通用的自适应方案（分栏 / 铰链 / 后置屏）以后单独设计，不在这里预留分叉。
 */
@Composable
internal fun MainLayout(
    phoneTab: Int,
    onPhoneTabSelected: (Int) -> Unit,
    onViewSheetRequest: () -> Unit,
    chromeHidden: Boolean,
    chromeMode: ChromeMode,
    onNavigationRequested: () -> Unit,
    dayCard: @Composable () -> Unit,
    taskCard: @Composable () -> Unit,
) {
    // 系统栏只在这里让位一次：键盘的内边距归 Note 面板自己处理，避免叠加。
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
            val tabStateHolder = rememberSaveableStateHolder()
            tabStateHolder.SaveableStateProvider(phoneTab) {
                // 时间轴与 Note 已经在同一个界面里，第一格就是"今天"。
                when (phoneTab) {
                    1 -> taskCard()
                    else -> dayCard()
                }
            }
        }

        PhoneTabBar(
            selected = phoneTab,
            onSelect = { selected ->
                if (selected == 1 && phoneTab == 1) onViewSheetRequest()
                onPhoneTabSelected(selected)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            presentation = when {
                chromeHidden -> PhoneTabBarPresentation.Hidden
                chromeMode == ChromeMode.PrimaryNavigation ->
                    PhoneTabBarPresentation.Expanded
                else -> PhoneTabBarPresentation.HandleOnly
            },
            onExpandRequest = onNavigationRequested,
        )
    }
}
