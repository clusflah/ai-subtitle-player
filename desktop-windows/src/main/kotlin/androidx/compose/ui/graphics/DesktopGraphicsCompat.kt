package androidx.compose.ui.graphics

import android.graphics.Bitmap
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader

fun Bitmap.asImageBitmap(): ImageBitmap {
    cachedComposeBitmap?.let { return it }
    return bufferedImage.toComposeImageBitmap().also { cachedComposeBitmap = it }
}

fun ImageBitmap.asAndroidBitmap(): Bitmap {
    return Bitmap(this.toAwtImage())
}

fun AndroidRenderEffect.asComposeRenderEffect(): RenderEffect {
    return skiaFilter.asComposeRenderEffect()
}

fun ShaderBrush(shader: RuntimeShader): ShaderBrush {
    val skiaShader = shader.makeSkiaShader()
    return if (skiaShader != null) {
        ShaderBrush(skiaShader)
    } else {
        object : ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size): Shader {
                return org.jetbrains.skia.Shader.makeColor(0)
            }
        }
    }
}
