package com.fakepod.remote

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Folding changes visibility only: fields and their values remain attached. */
internal class NasFormSection(context: Context, title: String, initiallyExpanded: Boolean,
    private val summary: () -> String) : LinearLayout(context) {
    val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(8)) }
    private val arrow = TextView(context).apply { textSize = 17f; setTextColor(Color.LTGRAY) }
    private val heading = TextView(context).apply {
        text = title; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
    }
    private val detail = TextView(context).apply {
        textSize = 11f; setTextColor(Color.rgb(171, 194, 218)); maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54); setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true; isFocusable = true
        background = GradientDrawable().apply { setColor(Color.rgb(33, 42, 55)); cornerRadius = dp(10).toFloat() }
    }
    var expanded = initiallyExpanded
        set(value) { field = value; refreshSummary() }

    init {
        orientation = VERTICAL
        header.addView(arrow, LayoutParams(dp(25), -2))
        val captions = LinearLayout(context).apply { orientation = VERTICAL }
        captions.addView(heading); captions.addView(detail)
        header.addView(captions, LayoutParams(0, -2, 1f))
        addView(header); addView(body)
        header.setOnClickListener { expanded = !expanded }
        refreshSummary()
    }

    fun refreshSummary() {
        arrow.text = if (expanded) "▾" else "▸"
        detail.text = summary()
        detail.visibility = if (expanded || detail.text.isNullOrBlank()) View.GONE else View.VISIBLE
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        header.contentDescription = "${heading.text}，${detail.text}，${if (expanded) "已展开，点击收起" else "已收起，点击展开"}"
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
}
