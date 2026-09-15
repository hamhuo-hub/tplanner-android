package com.hamhuo.tplanner

import android.content.Context
import android.graphics.Canvas
import android.view.SurfaceHolder
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime

/** Hop reads the same canonical documents as the Wear app and computes its own timeline. */
class FaceHop(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.HOP) {
    private val painter = HopFacePainter(context)
    private val burnInProtection = watchState.hasBurnInProtection
    private val lowBitAmbient = watchState.hasLowBitAmbient

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        draw(canvas, t, false)
    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        draw(canvas, t, true)
    }

    @Suppress("DEPRECATION")
    private fun draw(canvas: Canvas, t: ZonedDateTime, ambient: Boolean) {
        val dm = context.resources.displayMetrics
        painter.draw(
            canvas, HopFaceMetrics(faceW.toFloat(), faceH.toFloat(), dm.density, dm.scaledDensity),
            t, marks.items.filter { it.scheduled }.map {
                HopTaskInterval(it.uid, it.title, it.startEpochMs!!, it.endEpochMs ?: it.startEpochMs)
            },
            ambient = ambient, burnInProtection = burnInProtection, lowBitAmbient = lowBitAmbient,
        )
    }

    // The subtle schedule area remains a usable entrance even when labels cannot fit.
    override fun isOnAppLaunchRegion(x: Int, y: Int): Boolean {
        val r = minOf(faceW, faceH) * 0.45f
        val dx = x - faceW / 2f
        val dy = y - faceH / 2f
        return r > 0f && dx * dx + dy * dy <= r * r
    }
}
