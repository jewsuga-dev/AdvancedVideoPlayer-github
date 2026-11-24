package com.advancedvideoplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advancedvideoplayer.data.SpecialFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Finds and decodes the logo for a [SpecialFormat] from assets/logos/.
 *
 * The old code built the asset path from a hard-coded file name ("logos/dolby_atmos.png",
 * "logos/dts_hd.png" …) while the shipped files are "Dolby-Atmos-Logo.png", "DTS_HD.png" – on
 * Android's case-sensitive asset system that is simply a miss, so the logo "failed
 * intermittently" (depending on whether a text fallback happened to be drawn). Files are now
 * looked up by *normalised* name (see [SpecialFormat.logoKeys]) so any reasonable file name
 * works, and the lookup is done once.
 */
/** A decoded logo plus whether its artwork is dark (needs a light backing to be visible). */
class LoadedLogo(val bitmap: ImageBitmap, val darkArtwork: Boolean)

object LogoRepository {

    private const val DIR = "logos"

    @Volatile private var index: Map<String, String>? = null
    private val bitmaps = LruCache<String, LoadedLogo>(24)
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** "Dolby-Atmos-Logo.png" → "dolbyatmos" */
    internal fun normalize(fileName: String): String {
        val base = fileName.substringBeforeLast('.').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return if (base.length > 4 && base.endsWith("logo")) base.removeSuffix("logo") else base
    }

    private fun index(context: Context): Map<String, String> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val built = try {
                context.applicationContext.assets.list(DIR).orEmpty()
                    .filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS }
                    .associateBy({ normalize(it) }, { "$DIR/$it" })
            } catch (_: Throwable) { emptyMap() }
            index = built
            return built
        }
    }

    /** Asset path of the logo file for [format], or null when the project ships none. */
    fun resolve(context: Context, format: SpecialFormat): String? {
        val idx = index(context)
        return format.logoKeys.firstNotNullOfOrNull { idx[it] }
    }

    /** Decodes the logo (cached). Null if there is none or it cannot be decoded – never throws. */
    suspend fun load(context: Context, format: SpecialFormat): LoadedLogo? {
        val path = resolve(context, format) ?: return null
        bitmaps.get(path)?.let { return it }
        if (path in failed) return null
        return withContext(Dispatchers.IO) {
            try {
                val bmp = context.applicationContext.assets.open(path).use { BitmapFactory.decodeStream(it) }
                if (bmp == null) {
                    failed += path
                    null
                } else {
                    LoadedLogo(bmp.asImageBitmap(), isDarkArtwork(bmp)).also { bitmaps.put(path, it) }
                }
            } catch (t: Throwable) {
                failed += path
                null
            }
        }
    }

    /** True when the visible (non-transparent) pixels are mostly dark, e.g. black Dolby Vision text. */
    private fun isDarkArtwork(bmp: android.graphics.Bitmap): Boolean {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 32, true)
        var sum = 0.0
        var n = 0
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            if ((p ushr 24) < 128) continue
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            n++
        }
        if (small !== bmp) small.recycle()
        return n == 0 || sum / n < 128
    }

    private val EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")
}

private sealed interface LogoState {
    data object Loading : LogoState
    data class Ready(val logo: LoadedLogo) : LogoState
    data object Missing : LogoState
}

/** Every logo / badge is drawn in a cell of exactly this size so the row looks uniform. */
object LogoCell {
    fun width(isTv: Boolean) = if (isTv) 150.dp else 104.dp
    fun height(isTv: Boolean) = if (isTv) 54.dp else 38.dp
}

/**
 * A logo in a fixed-size rounded cell. The cell background adapts to the artwork: dark logos
 * (black "Dolby Vision" text …) get a light backing, light logos a dark one, so they are legible
 * on any video frame. No asset → a coloured text badge of the same size. Never empty, never crashes.
 */
@Composable
fun FormatBadge(format: SpecialFormat, isTv: Boolean = false) {
    val context = LocalContext.current
    val hasAsset = remember(format) { LogoRepository.resolve(context, format) != null }

    if (!hasAsset) {
        TextBadge(format, isTv)
        return
    }
    val state by produceState<LogoState>(LogoState.Loading, format) {
        val logo = LogoRepository.load(context, format)
        value = if (logo != null) LogoState.Ready(logo) else LogoState.Missing
    }
    val w = LogoCell.width(isTv)
    val h = LogoCell.height(isTv)
    when (val s = state) {
        is LogoState.Ready -> Box(
            modifier = Modifier
                .size(w, h)
                .clip(RoundedCornerShape(8.dp))
                .background(if (s.logo.darkArtwork) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = s.logo.bitmap,
                contentDescription = format.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        LogoState.Loading -> Spacer(Modifier.size(w, h)) // reserve space, no flicker
        LogoState.Missing -> TextBadge(format, isTv)
    }
}

@Composable
private fun TextBadge(format: SpecialFormat, isTv: Boolean) {
    val bg = when (format.family) {
        SpecialFormat.Family.DOLBY -> Color(0xFF00B4E4)
        SpecialFormat.Family.DTS -> Color(0xFFE31C25)
        SpecialFormat.Family.HDR -> Color(0xFFB8860B)
        SpecialFormat.Family.CODEC -> Color(0xFF7B61FF)
    }
    Box(
        modifier = Modifier
            .size(LogoCell.width(isTv), LogoCell.height(isTv))
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = format.displayName,
            color = Color.White,
            fontSize = if (isTv) 16.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
