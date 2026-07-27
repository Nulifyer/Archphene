package org.archphene.app.appearance

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.archphene.app.R

internal class LinuxAppearanceSettingsView(
    context: Context,
) : ScrollView(context) {
    init {
        val overrides = LinuxAppearancePreferences.read(context)
        isFillViewport = true
        setBackgroundColor(context.getColor(R.color.archphene_background))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(24))
                addView(
                    heading(R.string.appearance_heading),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(56),
                    ),
                )
                addView(
                    TextView(context).apply {
                        setText(R.string.appearance_description)
                        setTextColor(context.getColor(R.color.archphene_on_surface_muted))
                        textSize = 15f
                        setPadding(dp(4), 0, dp(4), dp(12))
                    },
                )
                addAppearanceSlider(
                    R.string.linux_geometry_scale,
                    R.string.linux_geometry_scale_description,
                    LinuxAppearancePreferences.GEOMETRY_PERCENT,
                    LinuxAppearancePreferences.geometryValues,
                    overrides.geometryPercent,
                ) { value ->
                    if (value == LinuxAppearancePreferences.AUTO) {
                        context.getString(R.string.appearance_automatic)
                    } else {
                        context.getString(R.string.appearance_percent, value)
                    }
                }
                addAppearanceSlider(
                    R.string.linux_text_scale,
                    R.string.linux_text_scale_description,
                    LinuxAppearancePreferences.FONT_PERCENT,
                    LinuxAppearancePreferences.fontValues,
                    overrides.fontPercent,
                ) { value ->
                    if (value == LinuxAppearancePreferences.AUTO) {
                        context.getString(R.string.appearance_automatic)
                    } else {
                        context.getString(R.string.appearance_percent, value)
                    }
                }
                addAppearanceSlider(
                    R.string.linux_control_size,
                    R.string.linux_control_size_description,
                    LinuxAppearancePreferences.CONTROL_VISUAL_DP,
                    LinuxAppearancePreferences.controlValues,
                    overrides.controlVisualDp,
                ) { value ->
                    if (value == LinuxAppearancePreferences.AUTO) {
                        context.getString(R.string.appearance_automatic_phone_controls)
                    } else {
                        context.getString(R.string.appearance_dp, value)
                    }
                }
                addView(
                    TextView(context).apply {
                        setText(R.string.appearance_relaunch_notice)
                        setTextColor(context.getColor(R.color.archphene_on_surface_muted))
                        textSize = 14f
                        setPadding(dp(4), dp(16), dp(4), 0)
                    },
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun heading(textResource: Int): TextView =
        TextView(context).apply {
            setText(textResource)
            setTextColor(context.getColor(R.color.archphene_on_surface))
            textSize = 22f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            maxLines = 1
        }

    private fun LinearLayout.addAppearanceSlider(
        titleResource: Int,
        descriptionResource: Int,
        preferenceKey: String,
        values: IntArray,
        initialValue: Int,
        formatValue: (Int) -> String,
    ) {
        val valueView =
            TextView(context).apply {
                setTextColor(context.getColor(R.color.archphene_primary))
                textSize = 16f
                gravity = Gravity.END
                maxLines = 1
            }
        val seekBar =
            SeekBar(context).apply {
                max = values.lastIndex
                progress = values.indexOf(initialValue).coerceAtLeast(0)
                splitTrack = false
                thumbTintList =
                    ColorStateList.valueOf(context.getColor(R.color.archphene_primary))
                progressTintList =
                    ColorStateList.valueOf(context.getColor(R.color.archphene_primary))
                progressBackgroundTintList =
                    ColorStateList.valueOf(context.getColor(R.color.archphene_on_surface_muted))
                tickMark =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(context.getColor(R.color.archphene_on_surface_muted))
                        setSize(dp(4), dp(4))
                    }
                setPadding(dp(8), 0, dp(8), 0)
            }
        fun updateValue(progress: Int) {
            val value = values[progress.coerceIn(0, values.lastIndex)]
            val formatted = formatValue(value)
            valueView.text = formatted
            seekBar.contentDescription =
                context.getString(
                    R.string.appearance_slider_accessibility,
                    context.getString(titleResource),
                    formatted,
                )
        }
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    updateValue(progress)
                    if (fromUser) {
                        context
                            .getSharedPreferences(
                                LinuxAppearancePreferences.PREFERENCES,
                                Context.MODE_PRIVATE,
                            ).edit()
                            .putInt(preferenceKey, values[progress])
                            .apply()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            },
        )
        updateValue(seekBar.progress)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        setColor(context.getColor(R.color.archphene_surface))
                    }
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            TextView(context).apply {
                                setText(titleResource)
                                setTextColor(context.getColor(R.color.archphene_on_surface))
                                textSize = 17f
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ),
                        )
                        addView(
                            valueView,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    },
                )
                addView(
                    TextView(context).apply {
                        setText(descriptionResource)
                        setTextColor(context.getColor(R.color.archphene_on_surface_muted))
                        textSize = 14f
                        setPadding(0, dp(4), 0, dp(4))
                    },
                )
                addView(
                    seekBar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48),
                    ),
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            TextView(context).apply {
                                setText(R.string.appearance_automatic)
                                setTextColor(context.getColor(R.color.archphene_on_surface_muted))
                                textSize = 12f
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ),
                        )
                        addView(
                            TextView(context).apply {
                                text = formatValue(values.last())
                                setTextColor(context.getColor(R.color.archphene_on_surface_muted))
                                textSize = 12f
                            },
                        )
                    },
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
