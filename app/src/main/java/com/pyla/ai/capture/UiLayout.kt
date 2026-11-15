package com.pyla.ai.capture

/**
 * Size and vertical placement of the game's UI on a given frame.
 *
 * The reference layout is 16:9. On 16:9 and wider screens the game scales its UI with the frame
 * height and every reference coordinate maps by that one factor. On screens narrower than 16:9
 * (tablets, unfolded foldables) the game scales its UI with the frame WIDTH instead and pins it
 * to the nearest edge: measured on a 4:3 frame, seven controls and headings were 0.75x their
 * 16:9 size (= the width ratio), upper ones at their scaled distance from the top edge, lower
 * ones at their scaled distance from the bottom edge, each within one pixel.
 *
 * For 16:9 and wider frames every function here returns exactly the former height-only mapping.
 */
object UiLayout {
    private const val ASPECT_W = 16
    private const val ASPECT_H = 9

    /**
     * Narrower than 16:9 by more than 1 %. The margin keeps real 16:9 devices on the height-only
     * mapping: their even-sized capture is a hair off (1136x640 is 1.775:1).
     */
    fun isNarrow(width: Int, height: Int): Boolean =
        width.toLong() * ASPECT_H * 100 < height.toLong() * ASPECT_W * 99

    /** Frame pixels per reference unit, for a layout authored at 16:9 with height [referenceHeight]. */
    fun unit(width: Int, height: Int, referenceHeight: Double = 1080.0): Double =
        if (!isNarrow(width, height)) height / referenceHeight
        else width / (referenceHeight * ASPECT_W / ASPECT_H)

    /**
     * Frame y of reference coordinate [referenceY]. On narrow frames the upper third is pinned to
     * the top edge, the lower third to the bottom edge and the middle to the centre line.
     */
    fun y(referenceY: Double, width: Int, height: Int, referenceHeight: Double = 1080.0): Double {
        val unit = unit(width, height, referenceHeight)
        if (!isNarrow(width, height)) return referenceY * unit
        return anchored(referenceY, referenceY, unit, height, referenceHeight)
    }

    /** Frame y of the top of a reference region; the region's centre decides its edge. */
    fun top(regionY: Double, regionHeight: Double, width: Int, height: Int, referenceHeight: Double = 1080.0): Double {
        val unit = unit(width, height, referenceHeight)
        if (!isNarrow(width, height)) return regionY * unit
        return anchored(regionY, regionY + regionHeight / 2.0, unit, height, referenceHeight)
    }

    private fun anchored(value: Double, decides: Double, unit: Double, height: Int, referenceHeight: Double): Double = when {
        decides < referenceHeight / 3.0 -> value * unit
        decides > referenceHeight * 2.0 / 3.0 -> height - (referenceHeight - value) * unit
        else -> height / 2.0 + (value - referenceHeight / 2.0) * unit
    }
}
