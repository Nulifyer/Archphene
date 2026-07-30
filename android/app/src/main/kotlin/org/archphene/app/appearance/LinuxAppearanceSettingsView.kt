package org.archphene.app.appearance

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.archphene.app.ArchphenePreferences
import org.archphene.app.R

internal class LinuxAppearanceSettingsView(
    context: Context,
    initialOverrides: LinuxAppearanceOverrides,
    initialReducedIsolationElectron: Boolean,
) : ScrollView(context) {
    private val preferenceControls = arrayOfNulls<SeekBar>(4)
    private val materialYou = Switch(context)
    private val reducedIsolationElectron = Switch(context)

    init {
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
                    R.string.linux_color_scheme,
                    R.string.linux_color_scheme_description,
                    LinuxAppearancePreferences.THEME_MODE,
                    LinuxAppearancePreferences.themeValues,
                    initialOverrides.themeMode,
                    R.string.appearance_light,
                ) { value ->
                    context.getString(
                        when (value) {
                            LinuxAppearancePreferences.LIGHT -> R.string.appearance_light
                            LinuxAppearancePreferences.DARK -> R.string.appearance_dark
                            else -> R.string.appearance_automatic
                        },
                    )
                }
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
                            materialYou.apply {
                                setText(R.string.material_you_colors)
                                setTextColor(context.getColor(R.color.archphene_on_surface))
                                textSize = 17f
                                isChecked = initialOverrides.materialYou
                                setOnClickListener {
                                    ArchphenePreferences.setMaterialYou(isChecked)
                                }
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(48),
                            ),
                        )
                        addView(
                            TextView(context).apply {
                                setText(R.string.material_you_colors_description)
                                setTextColor(
                                    context.getColor(R.color.archphene_on_surface_muted),
                                )
                                textSize = 14f
                                setPadding(0, dp(4), 0, 0)
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
                addAppearanceSlider(
                    R.string.linux_geometry_scale,
                    R.string.linux_geometry_scale_description,
                    LinuxAppearancePreferences.GEOMETRY_PERCENT,
                    LinuxAppearancePreferences.geometryValues,
                    initialOverrides.geometryPercent,
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
                    initialOverrides.fontPercent,
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
                    initialOverrides.controlVisualDp,
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
                addView(
                    heading(R.string.compatibility_heading),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(56),
                    ).apply {
                        topMargin = dp(12)
                    },
                )
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
                            reducedIsolationElectron.apply {
                                setText(R.string.electron_compatibility)
                                setTextColor(context.getColor(R.color.archphene_on_surface))
                                textSize = 17f
                                isChecked = initialReducedIsolationElectron
                                setOnClickListener {
                                    if (isChecked) {
                                        isChecked = false
                                        AlertDialog.Builder(context)
                                            .setTitle(
                                                R.string.electron_compatibility_confirm_title,
                                            )
                                            .setMessage(R.string.electron_compatibility_warning)
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .setPositiveButton(R.string.enable) { _, _ ->
                                                isChecked = true
                                                ArchphenePreferences
                                                    .setReducedIsolationElectron(true)
                                            }
                                            .show()
                                    } else {
                                        ArchphenePreferences
                                            .setReducedIsolationElectron(false)
                                    }
                                }
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(48),
                            ),
                        )
                        addView(
                            TextView(context).apply {
                                setText(R.string.electron_compatibility_description)
                                setTextColor(
                                    context.getColor(R.color.archphene_on_surface_muted),
                                )
                                textSize = 14f
                                setPadding(0, dp(4), 0, 0)
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
        middleLabelResource: Int? = null,
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
        preferenceControls[preferenceIndex(preferenceKey)] = seekBar
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
                        ArchphenePreferences.setAppearance(preferenceKey, values[progress])
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
                        if (middleLabelResource != null) {
                            addView(
                                TextView(context).apply {
                                    setText(middleLabelResource)
                                    setTextColor(
                                        context.getColor(R.color.archphene_on_surface_muted),
                                    )
                                    textSize = 12f
                                    gravity = Gravity.CENTER
                                },
                                LinearLayout.LayoutParams(
                                    0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f,
                                ),
                            )
                        }
                        addView(
                            TextView(context).apply {
                                text = formatValue(values.last())
                                setTextColor(context.getColor(R.color.archphene_on_surface_muted))
                                textSize = 12f
                                gravity = Gravity.END
                            },
                            if (middleLabelResource == null) {
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                            } else {
                                LinearLayout.LayoutParams(
                                    0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f,
                                )
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

    internal fun applyPreferences(
        overrides: LinuxAppearanceOverrides,
        reducedIsolationElectron: Boolean,
    ) {
        updateControl(
            preferenceControls[0],
            LinuxAppearancePreferences.geometryValues,
            overrides.geometryPercent,
        )
        updateControl(
            preferenceControls[1],
            LinuxAppearancePreferences.fontValues,
            overrides.fontPercent,
        )
        updateControl(
            preferenceControls[2],
            LinuxAppearancePreferences.controlValues,
            overrides.controlVisualDp,
        )
        updateControl(
            preferenceControls[3],
            LinuxAppearancePreferences.themeValues,
            overrides.themeMode,
        )
        if (materialYou.isChecked != overrides.materialYou) {
            materialYou.isChecked = overrides.materialYou
        }
        if (this.reducedIsolationElectron.isChecked != reducedIsolationElectron) {
            this.reducedIsolationElectron.isChecked = reducedIsolationElectron
        }
    }

    private fun updateControl(
        control: SeekBar?,
        values: IntArray,
        value: Int,
    ) {
        val progress = values.indexOf(value).coerceAtLeast(0)
        if (control != null && control.progress != progress) {
            control.progress = progress
        }
    }

    private fun preferenceIndex(key: String): Int =
        when (key) {
            LinuxAppearancePreferences.GEOMETRY_PERCENT -> 0
            LinuxAppearancePreferences.FONT_PERCENT -> 1
            LinuxAppearancePreferences.CONTROL_VISUAL_DP -> 2
            LinuxAppearancePreferences.THEME_MODE -> 3
            else -> throw IllegalArgumentException("Unknown appearance preference")
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
