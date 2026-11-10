package android.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

object Color {
    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val TRANSPARENT: Int = 0x00000000

    @JvmStatic fun alpha(color: Int): Int = (color ushr 24)
    @JvmStatic fun red(color: Int): Int = (color shr 16) and 0xFF
    @JvmStatic fun green(color: Int): Int = (color shr 8) and 0xFF
    @JvmStatic fun blue(color: Int): Int = color and 0xFF
    @JvmStatic fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    @JvmStatic fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

class Matrix {
    var rotationDegrees: Float = 0f
        private set

    fun postRotate(degrees: Float): Boolean {
        rotationDegrees = (rotationDegrees + degrees) % 360f
        return true
    }
}

class Bitmap(val bufferedImage: BufferedImage) {
    enum class Config {
        ARGB_8888,
        RGB_565,
        ALPHA_8,
        HARDWARE,
    }

    enum class CompressFormat(val formatName: String) {
        JPEG("jpg"),
        PNG("png"),
        WEBP("png"),
    }

    val width: Int get() = bufferedImage.width
    val height: Int get() = bufferedImage.height
    val byteCount: Int get() = width * height * 4
    var isRecycled: Boolean = false
        private set

    @Volatile
    internal var cachedComposeBitmap: ImageBitmap? = null

    fun getPixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return bufferedImage.getRGB(x, y)
    }

    fun setPixel(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) {
            bufferedImage.setRGB(x, y, color)
            cachedComposeBitmap = null
        }
    }

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        bufferedImage.getRGB(x, y, width, height, pixels, offset, stride)
    }

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        return runCatching {
            val outImg = if (format == CompressFormat.JPEG && bufferedImage.type != BufferedImage.TYPE_INT_RGB) {
                val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val g = rgb.createGraphics()
                g.drawImage(bufferedImage, 0, 0, java.awt.Color.BLACK, null)
                g.dispose()
                rgb
            } else {
                bufferedImage
            }
            ImageIO.write(outImg, format.formatName, stream)
        }.getOrDefault(false)
    }

    fun recycle() {
        isRecycled = true
    }

    fun toBufferedImage(): BufferedImage = bufferedImage

    fun copy(config: Config, isMutable: Boolean): Bitmap = this

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            return Bitmap(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB))
        }

        @JvmStatic
        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, colors, 0, w)
            return Bitmap(img)
        }

        @JvmStatic
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int, m: Matrix?, filter: Boolean): Bitmap {
            val rot = m?.rotationDegrees ?: 0f
            val sub = if (x == 0 && y == 0 && width == source.width && height == source.height) {
                source.bufferedImage
            } else {
                source.bufferedImage.getSubimage(
                    x.coerceIn(0, source.width - 1),
                    y.coerceIn(0, source.height - 1),
                    width.coerceIn(1, source.width - x),
                    height.coerceIn(1, source.height - y),
                )
            }
            if (rot == 0f) return Bitmap(sub)
            val rads = Math.toRadians(rot.toDouble())
            val sin = kotlin.math.abs(kotlin.math.sin(rads))
            val cos = kotlin.math.abs(kotlin.math.cos(rads))
            val nw = (sub.width * cos + sub.height * sin).toInt().coerceAtLeast(1)
            val nh = (sub.height * cos + sub.width * sin).toInt().coerceAtLeast(1)
            val rotated = BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB)
            val g2d = rotated.createGraphics()
            val at = AffineTransform()
            at.translate((nw - sub.width) / 2.0, (nh - sub.height) / 2.0)
            at.rotate(rads, sub.width / 2.0, sub.height / 2.0)
            g2d.drawRenderedImage(sub, at)
            g2d.dispose()
            return Bitmap(rotated)
        }

        @JvmStatic
        fun fromBufferedImage(img: BufferedImage): Bitmap = Bitmap(img)

        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
            val w = dstWidth.coerceAtLeast(1)
            val h = dstHeight.coerceAtLeast(1)
            if (src.width == w && src.height == h) return src
            val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g: Graphics2D = scaled.createGraphics()
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            g.drawImage(src.bufferedImage, 0, 0, w, h, null)
            g.dispose()
            return Bitmap(scaled)
        }
    }
}

object BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var inSampleSize: Int = 1
        var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
        var outWidth: Int = 0
        var outHeight: Int = 0
        var outMimeType: String? = null
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? {
        val slice = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        if (opts?.inJustDecodeBounds == true) {
            runCatching {
                org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(slice)).use { codec ->
                    opts.outWidth = codec.width
                    opts.outHeight = codec.height
                    return null
                }
            }
            runCatching {
                ImageIO.createImageInputStream(ByteArrayInputStream(slice))?.use { iis ->
                    val readers = ImageIO.getImageReaders(iis)
                    if (readers.hasNext()) {
                        val reader = readers.next()
                        try {
                            reader.input = iis
                            opts.outWidth = reader.getWidth(0)
                            opts.outHeight = reader.getHeight(0)
                        } finally {
                            reader.dispose()
                        }
                    }
                }
            }
            return null
        }

        val rawBitmap = decodeBytesToBufferedImage(slice) ?: return null
        opts?.outWidth = rawBitmap.width
        opts?.outHeight = rawBitmap.height
        val sample = (opts?.inSampleSize ?: 1).coerceAtLeast(1)
        val bmp = Bitmap(rawBitmap)
        return if (sample > 1) {
            Bitmap.createScaledBitmap(bmp, (bmp.width / sample).coerceAtLeast(1), (bmp.height / sample).coerceAtLeast(1), true)
        } else {
            bmp
        }
    }

    @JvmStatic
    fun decodeFile(pathName: String?, opts: Options? = null): Bitmap? {
        if (pathName.isNullOrBlank()) return null
        val file = File(pathName)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    @JvmStatic
    fun decodeStream(isStream: InputStream?, outPadding: Any? = null, opts: Options? = null): Bitmap? {
        if (isStream == null) return null
        return runCatching {
            val bytes = isStream.readBytes()
            decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    internal fun decodeBytesToBufferedImage(bytes: ByteArray): BufferedImage? {
        val directlyRead = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (directlyRead != null) return directlyRead

        // 用 Skia 解码（支持 WebP / AVIF 等格式），转成 PNG 字节流让 ImageIO 读取为 BufferedImage
        return runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { skiaImg ->
                val pngData = skiaImg.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
                if (pngData != null) {
                    ImageIO.read(ByteArrayInputStream(pngData.bytes))
                } else null
            }
        }.getOrNull()
    }
}

object ImageDecoder {
    const val ALLOCATOR_SOFTWARE = 1

    class Source internal constructor(internal val bytes: ByteArray)

    class ImageInfo(val size: android.util.Size)

    class Decoder {
        var allocator: Int = ALLOCATOR_SOFTWARE
        var targetWidth: Int = 0
        var targetHeight: Int = 0
        var targetSampleSize: Int = 1
            private set

        fun setTargetSize(width: Int, height: Int) {
            targetWidth = width
            targetHeight = height
        }

        fun setTargetSampleSize(sampleSize: Int) {
            targetSampleSize = sampleSize.coerceAtLeast(1)
        }
    }

    fun interface OnHeaderDecodedListener {
        fun onHeaderDecoded(decoder: Decoder, info: ImageInfo, source: Source)
    }

    @JvmStatic
    fun createSource(buffer: ByteBuffer): Source {
        val dup = buffer.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return Source(bytes)
    }

    @JvmStatic
    fun decodeBitmap(source: Source, listener: OnHeaderDecodedListener? = null): Bitmap {
        val bmp = BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)
            ?: throw java.io.IOException("无法解码图片")
        if (listener != null) {
            val decoder = Decoder()
            listener.onHeaderDecoded(decoder, ImageInfo(android.util.Size(bmp.width, bmp.height)), source)
            if (decoder.targetWidth > 0 && decoder.targetHeight > 0) {
                return Bitmap.createScaledBitmap(bmp, decoder.targetWidth, decoder.targetHeight, true)
            } else if (decoder.targetSampleSize > 1) {
                return Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width / decoder.targetSampleSize).coerceAtLeast(1),
                    (bmp.height / decoder.targetSampleSize).coerceAtLeast(1),
                    true
                )
            }
        }
        return bmp
    }
}

open class Shader {
    enum class TileMode {
        CLAMP,
        REPEAT,
        MIRROR,
        DECAL,
    }
}

class RuntimeShader(sksl: String) : Shader() {
    private val effect: org.jetbrains.skia.RuntimeEffect? = runCatching {
        org.jetbrains.skia.RuntimeEffect.makeForShader(sksl)
    }.getOrNull()

    private val builder: org.jetbrains.skia.RuntimeShaderBuilder? = effect?.let {
        org.jetbrains.skia.RuntimeShaderBuilder(it)
    }

    fun setFloatUniform(uniformName: String, value: Float) {
        runCatching { builder?.uniform(uniformName, value) }
    }

    fun setFloatUniform(uniformName: String, v1: Float, v2: Float) {
        runCatching { builder?.uniform(uniformName, v1, v2) }
    }

    fun setFloatUniform(uniformName: String, v1: Float, v2: Float, v3: Float) {
        runCatching { builder?.uniform(uniformName, v1, v2, v3) }
    }

    fun setFloatUniform(uniformName: String, v1: Float, v2: Float, v3: Float, v4: Float) {
        runCatching { builder?.uniform(uniformName, v1, v2, v3, v4) }
    }

    fun setIntUniform(uniformName: String, value: Int) {
        runCatching { builder?.uniform(uniformName, value) }
    }

    internal fun makeSkiaShader(): org.jetbrains.skia.Shader? = runCatching {
        builder?.makeShader()
    }.getOrNull()
}

class RenderEffect internal constructor(internal val skiaFilter: org.jetbrains.skia.ImageFilter) {
    companion object {
        @JvmStatic
        fun createBlurEffect(radiusX: Float, radiusY: Float, edgeTreatment: Shader.TileMode): RenderEffect {
            val mode = when (edgeTreatment) {
                Shader.TileMode.CLAMP -> org.jetbrains.skia.FilterTileMode.CLAMP
                Shader.TileMode.REPEAT -> org.jetbrains.skia.FilterTileMode.REPEAT
                Shader.TileMode.MIRROR -> org.jetbrains.skia.FilterTileMode.MIRROR
                Shader.TileMode.DECAL -> org.jetbrains.skia.FilterTileMode.DECAL
            }
            return RenderEffect(org.jetbrains.skia.ImageFilter.makeBlur(radiusX, radiusY, mode))
        }
    }
}
