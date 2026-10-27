package com.maintiflow.pro.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maintiflow.pro.ui.theme.StatusStyle
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusBadge(status: String) =
    Badge(StatusStyle.label[status] ?: status, StatusStyle.color[status] ?: Color.Gray)

@Composable
fun PriorityBadge(priority: String) =
    Badge(priority, StatusStyle.priorityColor[priority] ?: Color.Gray)

/** Bouton de 64 dp de haut minimum : utilisable avec des gants légers. */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true,
              container: Color? = null, onClick: () -> Unit) {
    val mod = modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (primary) {
        Button(onClick, mod, enabled = enabled, shape = RoundedCornerShape(12.dp),
            colors = if (container != null) ButtonDefaults.buttonColors(containerColor = container)
            else ButtonDefaults.buttonColors()) {
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(onClick, mod, enabled = enabled, shape = RoundedCornerShape(12.dp)) {
            Text(text, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun LabelValue(label: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.takeIf { it.isNotBlank() } ?: "—", Modifier.weight(0.58f), fontWeight = FontWeight.Medium)
    }
}

private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val fmtTime = DateTimeFormatter.ofPattern("HH:mm")

fun formatDate(iso: String?): String =
    iso?.let { runCatching { fmt.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull() } ?: "—"

fun formatTime(iso: String?): String =
    iso?.let { runCatching { fmtTime.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull() } ?: "—"

fun formatDuration(seconds: Long?): String {
    if (seconds == null) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h h ${"%02d".format(m)} min" else "$m min"
}

fun sinceText(iso: String): String = runCatching {
    formatDuration(Duration.between(Instant.parse(iso), Instant.now()).seconds.coerceAtLeast(0))
}.getOrDefault("")

/** Zone de signature manuscrite ; exportée en PNG base64 pour le backend. */
class SignatureState {
    val strokes = mutableStateListOf<List<Offset>>()
    val current = mutableStateListOf<Offset>()
    val isEmpty get() = strokes.isEmpty() && current.isEmpty()
    fun clear() { strokes.clear(); current.clear() }

    fun toPngBase64(width: Int = 600, height: Int = 200, srcW: Float, srcH: Float): String? {
        if (isEmpty) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = Paint().apply {
            color = android.graphics.Color.rgb(11, 61, 92); strokeWidth = 5f; style = Paint.Style.STROKE
            isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val sx = width / srcW
        val sy = height / srcH
        for (s in strokes) {
            val path = android.graphics.Path()
            s.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x * sx, o.y * sy) else path.lineTo(o.x * sx, o.y * sy) }
            c.drawPath(path, p)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

@Composable
fun SignaturePad(state: SignatureState, modifier: Modifier = Modifier, onSize: (Float, Float) -> Unit) {
    val ink = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    onSize(size.width.toFloat(), size.height.toFloat())
                    detectDragGestures(
                        onDragStart = { state.current.clear(); state.current.add(it) },
                        onDragEnd = { state.strokes.add(state.current.toList()); state.current.clear() },
                        onDrag = { change, _ -> state.current.add(change.position) },
                    )
                },
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val all = state.strokes + listOf(state.current.toList())
                for (s in all) {
                    if (s.size < 2) continue
                    val path = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, ink, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.clear() }) { Text("Effacer") }
        }
    }
}
