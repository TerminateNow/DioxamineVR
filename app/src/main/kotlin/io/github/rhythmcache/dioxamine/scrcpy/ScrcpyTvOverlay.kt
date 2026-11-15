package io.github.rhythmcache.dioxamine.scrcpy

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// Stream statistics
// ---------------------------------------------------------------------------

/** Counters fed by the scrcpy decoders/control channel; read by [StreamStatsPanel]. */
object ScrcpyStreamStats {
    /** Encoded video bytes received (packet headers + payload). */
    val videoBytes = AtomicLong()
    /** Encoded audio bytes received. */
    val audioBytes = AtomicLong()
    /** Control bytes sent to the mirrored device. */
    val controlTxBytes = AtomicLong()
    /** Decoded frames released to the screen. */
    val framesRendered = AtomicLong()

    fun reset() {
        videoBytes.set(0); audioBytes.set(0); controlTxBytes.set(0); framesRendered.set(0)
    }
}

private data class StatsSnapshot(
    val fps: Float = 0f,
    val bitrateMbps: Float = 0f,
    val rxMbps: Float = 0f,
    val txMbps: Float = 0f,
)

/**
 * Small top-left panel: rendered FPS, video bitrate, and network RX/TX between the two
 * devices. Network uses this app's total traffic (which is the ADB link while mirroring);
 * if the platform doesn't report per-app traffic it falls back to the stream counters.
 */
@Composable
fun StreamStatsPanel(modifier: Modifier = Modifier) {
    var snap by remember { mutableStateOf(StatsSnapshot()) }

    LaunchedEffect(Unit) {
        val uid = Process.myUid()
        fun rx(): Long = TrafficStats.getUidRxBytes(uid)
        fun tx(): Long = TrafficStats.getUidTxBytes(uid)
        val uidSupported = rx() != TrafficStats.UNSUPPORTED.toLong() && tx() != TrafficStats.UNSUPPORTED.toLong()

        var t0 = SystemClock.elapsedRealtime()
        var frames0 = ScrcpyStreamStats.framesRendered.get()
        var video0 = ScrcpyStreamStats.videoBytes.get()
        var audio0 = ScrcpyStreamStats.audioBytes.get()
        var ctl0 = ScrcpyStreamStats.controlTxBytes.get()
        var rx0 = if (uidSupported) rx() else 0L
        var tx0 = if (uidSupported) tx() else 0L

        while (true) {
            delay(1000)
            val t1 = SystemClock.elapsedRealtime()
            val dt = ((t1 - t0).coerceAtLeast(1)) / 1000f
            val frames1 = ScrcpyStreamStats.framesRendered.get()
            val video1 = ScrcpyStreamStats.videoBytes.get()
            val audio1 = ScrcpyStreamStats.audioBytes.get()
            val ctl1 = ScrcpyStreamStats.controlTxBytes.get()
            val rx1 = if (uidSupported) rx() else 0L
            val tx1 = if (uidSupported) tx() else 0L

            fun mbps(bytes: Long) = (bytes.coerceAtLeast(0) * 8f / dt) / 1_000_000f
            snap = StatsSnapshot(
                fps = (frames1 - frames0).coerceAtLeast(0) / dt,
                bitrateMbps = mbps(video1 - video0),
                rxMbps = if (uidSupported) mbps(rx1 - rx0) else mbps((video1 - video0) + (audio1 - audio0)),
                txMbps = if (uidSupported) mbps(tx1 - tx0) else mbps(ctl1 - ctl0),
            )
            t0 = t1; frames0 = frames1; video0 = video1; audio0 = audio1; ctl0 = ctl1; rx0 = rx1; tx0 = tx1
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.62f),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            StatRow("FPS", String.format(Locale.US, "%.0f", snap.fps))
            StatRow("Bitrate", String.format(Locale.US, "%.1f Mbps", snap.bitrateMbps))
            StatRow("RX", String.format(Locale.US, "%.2f Mbps", snap.rxMbps))
            StatRow("TX", String.format(Locale.US, "%.2f Mbps", snap.txMbps))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.width(56.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------
// Remote (mirrored device) volume slider
// ---------------------------------------------------------------------------

/**
 * Vertical volume slider for the mirrored device.
 *
 * D-pad: focus it, press Select to start adjusting (it lights up), then Up/Down change the
 * volume and Right toggles mute (restoring the previous level on unmute). Select again, Back,
 * Left or moving focus away stops adjusting.
 * Touch: tap or drag along the track.
 *
 * [level]/[max] are null when the device's absolute volume can't be read; Up/Down then send
 * volume key presses instead and the track shows no level.
 */
@Composable
fun RemoteVolumeSlider(
    level: Int?,
    max: Int?,
    adjusting: Boolean,
    onAdjustingChange: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onSetLevel: (Int) -> Unit,
    onActivity: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleMute: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val primary = MaterialTheme.colorScheme.primary
    val fraction = if (level != null && max != null && max > 0) level.toFloat() / max else null
    val animFraction by animateFloatAsState(fraction ?: 0f, tween(140), label = "volFraction")
    val accent by animateColorAsState(if (adjusting) primary else Color.White, tween(140), label = "volAccent")
    val currentMax by rememberUpdatedState(max)
    val currentOnSet by rememberUpdatedState(onSetLevel)

    val trackHeight = 168.dp

    Surface(
        modifier = modifier
            .border(
                width = if (adjusting) 2.dp else if (focused) 1.5.dp else 0.dp,
                color = if (adjusting) primary else if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(28.dp)
            )
            .onFocusChanged { if (!it.isFocused && adjusting) onAdjustingChange(false) }
            .onPreviewKeyEvent { e ->
                if (!adjusting || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { onStep(+1); onActivity(); true }
                    Key.DirectionDown -> { onStep(-1); onActivity(); true }
                    Key.DirectionRight -> { onToggleMute(); onActivity(); true }
                    else -> false
                }
            }
            .clip(RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button
            ) {
                onActivity()
                onAdjustingChange(!adjusting)
            },
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .width(56.dp)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = level?.toString() ?: "",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.height(18.dp)
            )
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(trackHeight)
                    .pointerInput(Unit) {
                        fun setFromY(y: Float) {
                            val m = currentMax ?: return
                            val f = (1f - y / size.height).coerceIn(0f, 1f)
                            currentOnSet((f * m).toInt())
                        }
                        detectTapGestures { setFromY(it.y); onActivity() }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, _ ->
                            change.consume()
                            val m = currentMax ?: return@detectVerticalDragGestures
                            val f = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            currentOnSet((f * m).toInt())
                            onActivity()
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                // track
                Box(
                    Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                )
                // fill
                Box(
                    Modifier
                        .width(8.dp)
                        .fillMaxHeight(animFraction)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent)
                )
                if (fraction == null) {
                    // Level unknown: show arrows hint instead of a fill
                    Text(
                        "▲\n▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            Icon(
                imageVector = if (level == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Device volume",
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Parses `cmd media_session volume --get` output, e.g. "volume is 7 in range [0..15]". */
internal fun parseMediaSessionVolume(output: String): Pair<Int, Int>? {
    val m = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""").find(output) ?: return null
    val level = m.groupValues[1].toIntOrNull() ?: return null
    val max = m.groupValues[3].toIntOrNull() ?: return null
    return if (max > 0) level to max else null
}
