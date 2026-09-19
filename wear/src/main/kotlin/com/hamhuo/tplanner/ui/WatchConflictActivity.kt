package com.hamhuo.tplanner

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens

/**
 * The user's decision about one synchronization conflict.
 *
 * Two different things are on offer, and neither is a CRDT winner: keeping the local intent means
 * abandoning the old command identity and submitting that intent again as a new command, which may
 * conflict again; using the server version means abandoning this device's intent and letting the
 * installed record become the fact. Both end with the outbox being nudged, because a conflict can
 * hold up the queue behind it.
 */
class WatchConflictActivity : WearPageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WatchV5Store.conflicts(this).isEmpty()) {
            finish()
            return
        }
        setContentView(
            WatchConflictView(
                context = this,
                load = { WatchV5Store.conflicts(this) },
                resolve = { conflict, reapply ->
                    WatchV5Store.resolveConflict(this, conflict.uid, reapply)
                    // Even "use the server version" needs this: the conflict may be blocking the
                    // queue behind it, and the store must keep converging without the user waiting.
                    WatchTaskOutbox.resumePending(this)
                },
                onDone = { finish() },
            ),
        )
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, WatchConflictActivity::class.java)
    }
}

/** One conflict per screen; the next one replaces it, and the last one closes the screen. */
private class WatchConflictView(
    context: Context,
    private val load: () -> List<WatchConflict>,
    private val resolve: (WatchConflict, Boolean) -> Unit,
    private val onDone: () -> Unit,
) : FrameLayout(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    init {
        setBackgroundColor(WEAR_BG)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(18), 0, dp(18), dp(28))
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showNext()
    }

    private fun showNext() {
        val conflict = load().firstOrNull()
        if (conflict == null) {
            onDone()
            return
        }
        render(conflict)
    }

    private fun render(conflict: WatchConflict) {
        content.removeAllViews()
        content.addView(
            textView(TPlannerLightTokens.Platform.Wear.Typography.Heading.FontSize, PRIMARY, WEAR_SEMIBOLD).apply {
                text = context.getString(R.string.sync_conflict_title)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(35)
                bottomMargin = dp(7)
            },
        )
        content.addView(
            textView(TPlannerLightTokens.Platform.Wear.Typography.TaskTitle.FontSize, PRIMARY, WEAR_MEDIUM).apply {
                text = conflict.localTitle?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.sync_conflict_local_delete)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(13), dp(10), dp(13), dp(10))
                background = context.wearSurfaceBackground()
                minimumHeight = context.wearDp(TPlannerLightTokens.Platform.Wear.Geometry.TaskRowMinHeight)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(
            textView(TPlannerLightTokens.Platform.Wear.Typography.Body.FontSize, SECONDARY, WEAR_REGULAR).apply {
                text = context.getString(R.string.sync_conflict_explanation)
                setPadding(dp(13), 0, dp(13), 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            },
        )
        content.addView(
            button(context.getString(R.string.sync_conflict_keep_mine), primary = true) {
                resolve(conflict, true)
                showNext()
            },
            buttonParams(),
        )
        content.addView(
            button(context.getString(R.string.sync_conflict_use_server), primary = false) {
                resolve(conflict, false)
                showNext()
            },
            buttonParams().apply { topMargin = dp(7) },
        )
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            wearButtonStyle(primary = primary)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun textView(sizeSp: Float, color: Int, font: Typeface): TextView = TextView(context).apply {
        setTextColor(color)
        textSize = sizeSp
        typeface = font
        wearTextMetrics()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private const val PRIMARY = WEAR_PRIMARY
private const val SECONDARY = WEAR_DIM
