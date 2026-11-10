package androidx.compose.ui.viewinterop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aisub.player.desktop.DesktopPlayerEngine

@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    onReset: ((T) -> Unit)? = null,
    onRelease: (T) -> Unit = {},
    update: (T) -> Unit = {},
) {
    val context = LocalContext.current
    val view = remember { factory(context) }
    androidx.compose.runtime.DisposableEffect(view) {
        onDispose {
            onRelease(view)
        }
    }
    SideEffect {
        update(view)
    }

    when (view) {
        is PlayerView -> {
            val frame by DesktopPlayerEngine.currentFrameFlow.collectAsState()
            val scale = when (view.resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> ContentScale.Crop
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> ContentScale.FillBounds
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> ContentScale.FillWidth
                else -> ContentScale.Fit
            }
            Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                val img = frame
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = scale,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        is WebView -> {
            Box(
                modifier = modifier.background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = view.title?.takeIf { it.isNotBlank() } ?: "内置网页嗅探器 (Windows)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = view.url ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "已自动后台抓取页面并嗅探视频流（m3u8/mp4）；如需完整交互可调用系统浏览器，搭配「桌面全局字幕」实时生成字幕。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    val targetUrl = view.url
                    if (!targetUrl.isNullOrBlank()) {
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                        }) {
                            Text("在系统浏览器中打开")
                        }
                    }
                }
            }
        }
        else -> {
            Box(modifier)
        }
    }
}
