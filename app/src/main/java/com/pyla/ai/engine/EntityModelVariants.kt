package com.pyla.ai.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Reduced-canvas variants of the bundled entity model.
 *
 * The factory model is a static 640x640 network, but a landscape game frame only fills a
 * 640x360 strip of that canvas: almost half of every inference is spent on grey padding. The
 * variants are derived from the *same weights* (shared through one ONNX external-data file) with
 * only the canvas-dependent shape/anchor constants regenerated, so the detections are those of
 * the factory model (measured box delta <= 0.5 px for 640x384) at a fraction of the cost.
 *
 * Only the untouched factory model is ever replaced by a variant: a user-supplied model keeps
 * running exactly as configured.
 */
object EntityModelVariants {
    private const val TAG = "PylaEntityModel"
    private const val ASSET_DIR = "pyla/models/entity_variants"
    private const val DIR = "models/entity_variants"
    private const val WEIGHTS = "entity_weights.bin"
    private const val MARKER = ".installed"

    /** SHA-256 of the factory `mainInGameModel.onnx` the variants were derived from. */
    const val FACTORY_SHA256 = "e9e2394ab3b65c92b88334a4ecc6675c054ee717c5fea9f35ad8f111ba13a9a4"

    /** Asset generation: change whenever any shipped variant file changes. */
    private const val GENERATION = "616da8a29ba765af-1"

    data class Variant(val file: String, val width: Int, val height: Int)

    /** Ordered from most to least detailed. */
    val VARIANTS = listOf(
        Variant("entity_640x384.onnx", 640, 384),
        Variant("entity_512x288.onnx", 512, 288),
        Variant("entity_448x256.onnx", 448, 256),
    )

    /** Inference budget above which the next cheaper variant is tried. */
    const val BUDGET_MS = 180L

    /**
     * True when [Detect]'s own aspect-preserving fit of a [captureWidth] x [captureHeight] frame
     * uses (practically) the full canvas width, i.e. the variant only removes padding rows or,
     * for the narrower variants, trades resolution for speed. A frame that would be pillar-boxed
     * (16:10, 4:3, portrait) keeps the factory model. Two unused columns are tolerated because the
     * capture width is rounded down to an even number.
     */
    fun fits(variant: Variant, captureWidth: Int, captureHeight: Int): Boolean {
        if (captureWidth <= 0 || captureHeight <= 0) return false
        val scale = minOf(variant.height.toDouble() / captureHeight, variant.width.toDouble() / captureWidth)
        return (captureWidth * scale).toInt() >= variant.width - 2
    }

    /** Variants usable for this capture geometry, most detailed first. Empty = keep factory model. */
    fun candidates(captureWidth: Int, captureHeight: Int): List<Variant> =
        VARIANTS.filter { fits(it, captureWidth, captureHeight) }

    /**
     * Copies the variant files out of the APK once per [GENERATION]. Returns the directory, or
     * `null` when anything is missing or failed: the caller then keeps the factory model.
     */
    fun install(context: Context, root: File): File? = try {
        val dir = File(root, DIR)
        val marker = File(dir, MARKER)
        if (marker.isFile && marker.readText() == GENERATION &&
            (VARIANTS.map { it.file } + WEIGHTS).all { File(dir, it).isFile }
        ) {
            dir
        } else {
            val staging = File(root, "$DIR.staging")
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IllegalStateException("staging unavailable")
            for (name in VARIANTS.map { it.file } + WEIGHTS) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(staging, name).outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            File(staging, MARKER).writeText(GENERATION)
            dir.deleteRecursively()
            dir.parentFile?.mkdirs()
            if (!staging.renameTo(dir)) throw IllegalStateException("activation failed")
            Log.i(TAG, "Installed entity model variants ($GENERATION)")
            dir
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Entity model variants unavailable (${t.javaClass.simpleName}); using the factory model")
        null
    }

    /** True only for the byte-identical factory model. Any read problem means "not factory". */
    fun isFactoryModel(model: File): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == FACTORY_SHA256
    } catch (_: Throwable) {
        false
    }
}
