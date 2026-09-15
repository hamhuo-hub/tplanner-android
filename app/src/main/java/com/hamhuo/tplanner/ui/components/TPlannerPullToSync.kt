package com.hamhuo.tplanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.ACCENT_TEXT
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.SURFACE2

/**
 * One app-level pull gesture shared by Notes, Inbox, and Timeline.
 *
 * 下拉手势通过 Modifier 接入,没有圆形容器或指示器。同步进行中只显示一个
 * 静态「正在同步…」pill;完成/失败统一由顶部的 sync complete/failed 反馈呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TPlannerPullToSync(
    isSyncing: Boolean,
    operationId: String?,
    onSync: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // onRefresh is emitted only when the user releases a pull beyond the threshold.
    // Updating isSyncing controls gesture availability and the pill, never requests another sync.
    val gestureState = rememberPullToRefreshState()

    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = isSyncing,
            state = gestureState,
            enabled = enabled,
            onRefresh = onSync,
        ),
    ) {
        content()
        if (isSyncing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(SURFACE2, RoundedCornerShape(Tokens.Semantic.Radius.Pill.dp))
                    .semantics {
                        stateDescription = "sync-operation:${operationId.orEmpty()}"
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.sync_sending),
                    color = ACCENT_TEXT,
                    fontSize = Tokens.Platform.Phone.Typography.Meta.FontSize.sp,
                )
            }
        }
    }
}
