package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class PosterReplacementBackoff(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val failures = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }
    @Synchronized fun allowed(key: String) = now() >= (failures[key] ?: 0L)
    @Synchronized fun failed(key: String) { failures[key] = now() + 600_000L }
}

private val posterReplacementPermit = Semaphore(1)
private val posterReplacementBackoff = PosterReplacementBackoff()

@Composable
internal fun rememberIptvPosterVisibility(enabled: Boolean): Pair<Boolean, Modifier> {
    if (!enabled) return true to Modifier
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    val modifier = remember(view) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
                bounds.left < view.width && bounds.top < view.height
        }
    }
    return visible to modifier
}

/** Keep the original model displayed until a visible card's replacement is decoded and cached. */
@Composable
internal fun rememberProgressiveIptvPoster(
    target: ImageRequest, originalUrl: String?, width: Int, height: Int, enabled: Boolean, visible: Boolean,
    owner: Any
): ImageRequest {
    if (!enabled) return target
    val context = LocalContext.current
    val replacement = remember(target) {
        target.newBuilder()
            .memoryCacheKeyExtras(target.memoryCacheKeyExtras - CustomPosterFallbackInterceptor.FALLBACK_URL_KEY)
            .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).build()
    }
    val original = remember(target, originalUrl, width, height) {
        target.newBuilder().data(originalUrl).memoryCacheKey("${originalUrl}_${width}x${height}_v0")
            .memoryCacheKeyExtras(emptyMap()).build()
    }
    var displayed by remember(owner, originalUrl) { mutableStateOf<ImageRequest?>(null) }
    LaunchedEffect(owner, replacement, originalUrl, visible) {
        if (!visible || displayed == replacement) return@LaunchedEffect
        delay(500) // Fast scrolling cancels before a new image request starts.
        val key = replacement.data.toString()
        var attempted = false
        val loaded = try {
            posterReplacementPermit.withPermit {
                if (!posterReplacementBackoff.allowed(key)) false
                else {
                    attempted = true
                    val started = System.nanoTime()
                    val result = kotlinx.coroutines.withTimeoutOrNull(12_000) { context.imageLoader.execute(replacement) }
                    if (com.nuvio.tv.BuildConfig.DEBUG && result is SuccessResult) android.util.Log.d("IptvPoster",
                        "Replacement loaded source=${result.dataSource} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    result is SuccessResult
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (loaded) displayed = replacement else if (attempted) posterReplacementBackoff.failed(key)
    }
    return displayed ?: original
}
