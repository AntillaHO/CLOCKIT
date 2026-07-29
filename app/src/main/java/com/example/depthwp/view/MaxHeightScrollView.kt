package com.example.depthwp.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A [ScrollView] that stops growing past a fraction of the screen height.
 *
 * The editor sheet sizes itself to its content so short sections stay compact, but the text section
 * with the effects sub-panel expanded is taller than the screen — a plain wrap_content ScrollView
 * would push the "Als Wallpaper setzen" button off the bottom. Capping the height here lets the
 * content scroll internally while the button stays pinned and reachable.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** Share of the screen height the sheet body may occupy at most. */
    var maxHeightFraction: Float = 0.42f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        )
    }
}
