package com.pyla.ai.engine

import com.pyla.ai.config.PylaConfig
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

object PylaUtils {

    const val JOYSTICK_RADIUS = 75
    const val PLAYER_HIT_CIRCLE_RADIUS = 53

    private val rgbScratch = ThreadLocal<ByteArray>()

    fun argbToRgbMat(argb: IntArray, w: Int, h: Int): Mat {
        val needed = w * h * 3
        var bytes = rgbScratch.get()
        if (bytes == null || bytes.size != needed) {
            bytes = ByteArray(needed)
            rgbScratch.set(bytes)
        }
        var i = 0
        var j = 0
        val len = argb.size.coerceAtMost(w * h)
        while (j < len) {
            val px = argb[j++]
            bytes[i++] = (px and 0xFF).toByte()
            bytes[i++] = ((px shr 8) and 0xFF).toByte()
            bytes[i++] = ((px shr 16) and 0xFF).toByte()
        }
        val mat = Mat(h, w, CvType.CV_8UC3)
        mat.put(0, 0, bytes)
        return mat
    }

    fun frameToMat(frame: FrameSnapshot): Mat = argbToRgbMat(frame.argb, frame.width, frame.height)

    fun frameToBitmap(frame: FrameSnapshot): android.graphics.Bitmap {
        val src = frame.argb
        val px = IntArray(frame.width * frame.height)
        val len = px.size.coerceAtMost(src.size)
        for (i in 0 until len) {
            val p = src[i]
            px[i] = (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p shr 16) and 0xFF)
        }
        return android.graphics.Bitmap.createBitmap(px, frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    fun countHsvPixels(rgb: Mat, lowHsv: DoubleArray, highHsv: DoubleArray): Int {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(lowHsv[0], lowHsv[1], lowHsv[2]), Scalar(highHsv[0], highHsv[1], highHsv[2]), mask)
        val n = Core.countNonZero(mask)
        hsv.release(); mask.release()
        return n
    }

    fun countMaskPixels(mask: Mat, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val w = mask.cols(); val h = mask.rows()
        val nx1 = x1.coerceIn(0, w); val nx2 = x2.coerceIn(0, w)
        val ny1 = y1.coerceIn(0, h); val ny2 = y2.coerceIn(0, h)
        if (nx1 >= nx2 || ny1 >= ny2) return 0
        val sub = Mat(mask, org.opencv.core.Rect(nx1, ny1, nx2 - nx1, ny2 - ny1))
        val n = Core.countNonZero(sub)
        sub.release()
        return n
    }

    fun clamp(x: Float, low: Float, high: Float): Float = if (x < low) low else if (x > high) high else x

    fun configBool(value: Any?, default: Boolean = false): Boolean {
        if (value == null) return default
        if (value is Boolean) return value
        if (value is String) return value.trim().lowercase() in setOf("1", "true", "yes", "on")
        if (value is Number) return value.toInt() != 0
        return default
    }
}