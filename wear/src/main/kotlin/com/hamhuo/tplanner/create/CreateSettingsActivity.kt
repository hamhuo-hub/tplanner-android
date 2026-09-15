package com.hamhuo.tplanner
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import com.hamhuo.tplanner.designsystem.TPlannerCategories

/** Fifth destination: color and the final save action. */
class CreateSettingsActivity : WearPageActivity() {
    private lateinit var colorRow: LinearLayout
    private lateinit var colorValue: TextView
    private lateinit var saveButton: TextView
    private lateinit var route: CreationRoute

    private var colorId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_settings_page)

        route = intent.creationRouteOrNull()?.takeIf {
            it.title.isNotBlank() && (!it.hasTime || it.dateEpochDay != DATE_EPOCH_DAY_UNSET)
        } ?: run {
            finish()
            return
        }

        colorId = savedInstanceState?.getInt(STATE_COLOR_ID) ?: DEFAULT_COLOR_ID

        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_settings_page)))
            addView(createColorRow())
            addView(
                creationActionRow(R.string.task_create_save, primary = true) {
                    saveTask()
                }.also { saveButton = it },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(9)
                },
            )
            addView(creationBottomSpacer())
        }

        setContentView(creationScrollPage(content))
        renderColor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_COLOR_ID, colorId)
        super.onSaveInstanceState(outState)
    }

    private fun createColorRow(): LinearLayout {
        colorRow = creationSettingRow(
            titleRes = R.string.task_create_color,
            descriptionRes = R.string.task_create_color_description,
        )
        colorValue = colorRow.findViewWithTag(TAG_VALUE)
        colorRow.setOnClickListener {
            colorId = (colorId + 1) % TASK_COLOR_COUNT
            renderColor()
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return colorRow
    }

    private fun renderColor() {
        val names = resources.getStringArray(R.array.task_create_color_names)
        val safeColorId = colorId.coerceIn(0 until TASK_COLOR_COUNT)
        val value = names[safeColorId]
        colorValue.text = value
        val category = TPlannerCategories.forColorId(safeColorId)
        colorValue.setTextColor(category.foreground)
        colorRow.background = wearInteractiveBackground(fill = category.background, border = category.border)
        colorRow.contentDescription = getString(
            R.string.task_create_setting_accessibility,
            getString(R.string.task_create_color),
            value,
        )
    }

    private fun saveTask() {
        if (!saveButton.isEnabled) return
        saveButton.isEnabled = false
        saveButton.text = getString(R.string.task_create_saving)

        val start = if (route.hasTime && route.dateEpochDay != DATE_EPOCH_DAY_UNSET) {
            LocalDate.ofEpochDay(route.dateEpochDay)
                .atTime(route.hour, route.minute)
                .atZone(CREATION_ZONE)
        } else {
            null
        }

        val draft = WatchTaskDraft(
            id = route.id,
            title = route.title,
            startEpochMs = start?.toInstant()?.toEpochMilli(),
            endEpochMs = start?.plusHours(DEFAULT_DURATION_HOURS)?.toInstant()?.toEpochMilli(),
            colorId = colorId,
        )

        val queued = WatchTaskOutbox.enqueue(this, draft)
        if (!queued) {
            saveButton.isEnabled = true
            saveButton.text = getString(R.string.task_create_save)
            Toast.makeText(this, R.string.task_create_failed, Toast.LENGTH_SHORT).show()
            saveButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }

        saveButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Toast.makeText(this, R.string.task_create_queued, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val STATE_COLOR_ID = "color_id"
        private const val DEFAULT_COLOR_ID = 0
        private const val DEFAULT_DURATION_HOURS = 1L

        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateSettingsActivity::class.java).putCreationRoute(route)
    }
}
