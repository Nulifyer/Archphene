package org.archphene.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class LegacyMigrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LegacyPrototypeState.detectedMarker(File(applicationInfo.dataDir)) == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(48), dp(24), dp(32))
                addView(
                    TextView(this@LegacyMigrationActivity).apply {
                        setText(R.string.legacy_migration_title)
                        textSize = 24f
                        setTextColor(getColor(R.color.archphene_on_surface))
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    TextView(this@LegacyMigrationActivity).apply {
                        setText(R.string.legacy_migration_message)
                        textSize = 16f
                        setTextColor(getColor(R.color.archphene_on_surface))
                        setPadding(0, dp(24), 0, dp(24))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    Button(this@LegacyMigrationActivity).apply {
                        setText(R.string.open_app_settings)
                        setOnClickListener {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    Button(this@LegacyMigrationActivity).apply {
                        setText(R.string.close_archphene)
                        setOnClickListener { finishAndRemoveTask() }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                    },
                )
            }
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
