package dev.trooped.tvquickbars.notification

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.services.ComposeViewLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Always-on top overlay containing the local device clock and fixed notifications.
 * It deliberately uses the application's existing Home Assistant WebSocket event path;
 * this class itself never creates a network/WebSocket connection.
 */
object FixedNotificationController {
    private const val DEFAULT_TEXT_COLOR = "#FFFFFF"
    private const val DEFAULT_BORDER_COLOR = "#FFFFFF"
    private const val DEFAULT_BACKGROUND_COLOR = "#66000000"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    private var contextRef: WeakReference<Context>? = null
    private var windowManagerRef: WeakReference<WindowManager>? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: ComposeViewLifecycleOwner? = null

    private var notifications by mutableStateOf<Map<String, FixedNotification>>(emptyMap())
    private val expirationJobs = mutableMapOf<String, Job>()

    fun start(context: Context) {
        runOnMain {
            contextRef = WeakReference(context.applicationContext)
            windowManagerRef = WeakReference(
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            )
            ensureOverlay()
        }
    }

    fun stop() {
        runOnMain {
            expirationJobs.values.forEach { it.cancel() }
            expirationJobs.clear()
            notifications = emptyMap()

            val view = composeView
            composeView = null
            lifecycleOwner?.let {
                try { it.pause() } catch (_: Throwable) {}
                try { it.destroy() } catch (_: Throwable) {}
            }
            lifecycleOwner = null

            if (view != null) {
                try { view.setContent {} } catch (_: Throwable) {}
                try { windowManagerRef?.get()?.removeViewImmediate(view) } catch (_: Throwable) {}
            }
        }
    }

    fun updateFromEvent(context: Context, data: org.json.JSONObject) {
        start(context)
        runOnMain {
            val id = data.optString("id", "").ifBlank {
                "fixed_${System.currentTimeMillis()}_${(0..9999).random()}"
            }

            if (!data.optBoolean("visible", true)) {
                remove(id)
                return@runOnMain
            }

            val notification = FixedNotification(
                id = id,
                index = data.optInt("index", 0),
                icon = data.optString("icon", "").takeIf { it.isNotBlank() },
                message = data.optString("message", ""),
                messageColor = parseColor(
                    data.optString("messageColor", data.optString("textColor", "")),
                    DEFAULT_TEXT_COLOR
                ),
                iconColor = parseColor(data.optString("iconColor", ""), DEFAULT_TEXT_COLOR),
                borderColor = parseColor(data.optString("borderColor", ""), DEFAULT_BORDER_COLOR),
                backgroundColor = parseColor(
                    data.optString("backgroundColor", ""),
                    DEFAULT_BACKGROUND_COLOR
                ),
                shape = data.optString("shape", "rounded").lowercase().let {
                    if (it in setOf("circle", "rounded", "rectangular")) it else "rounded"
                }
            )

            notifications = notifications.toMutableMap().apply { put(id, notification) }
            scheduleExpiration(id, data.opt("expiration"))
            ensureOverlay()
        }
    }

    private fun remove(id: String) {
        expirationJobs.remove(id)?.cancel()
        notifications = notifications - id
        ensureOverlay()
    }

    private fun scheduleExpiration(id: String, raw: Any?) {
        expirationJobs.remove(id)?.cancel()
        val delayMs = parseExpirationDelayMs(raw) ?: return
        if (delayMs <= 0L) {
            remove(id)
            return
        }
        expirationJobs[id] = scope.launch {
            delay(delayMs)
            remove(id)
        }
    }

    private fun parseExpirationDelayMs(raw: Any?): Long? {
        if (raw == null || raw == org.json.JSONObject.NULL) return null
        val nowSec = System.currentTimeMillis() / 1000L

        if (raw is Number) {
            val value = raw.toLong()
            return if (value > 1_000_000_000L) {
                (value - nowSec).coerceAtLeast(0L) * 1000L
            } else {
                value.coerceAtLeast(0L) * 1000L
            }
        }

        val text = raw.toString().trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { value ->
            return if (value > 1_000_000_000L) {
                (value - nowSec).coerceAtLeast(0L) * 1000L
            } else {
                value.coerceAtLeast(0L) * 1000L
            }
        }

        val regex = Regex("(?i)(\\d+)(y|w|d|h|m|s)")
        var total = 0L
        regex.findAll(text).forEach { match ->
            val n = match.groupValues[1].toLong()
            total += when (match.groupValues[2].lowercase()) {
                "y" -> n * 365L * 24 * 60 * 60 * 1000
                "w" -> n * 7L * 24 * 60 * 60 * 1000
                "d" -> n * 24L * 60 * 60 * 1000
                "h" -> n * 60L * 60 * 1000
                "m" -> n * 60L * 1000
                else -> n * 1000L
            }
        }
        return total.takeIf { it > 0L }
    }

    private fun parseColor(value: String, fallback: String): Color {
        val raw = if (value.isBlank()) fallback else value.trim()
        val normalized = if (raw.startsWith("#")) raw else "#$raw"
        return try {
            Color(AndroidColor.parseColor(normalized))
        } catch (_: Throwable) {
            Color(AndroidColor.parseColor(fallback))
        }
    }

    private fun ensureOverlay() {
        val context = contextRef?.get() ?: return
        val wm = windowManagerRef?.get() ?: return
        if (!Settings.canDrawOverlays(context)) return
        if (composeView != null) return

        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_HAQuickBars)
        val view = ComposeView(themed)
        val owner = ComposeViewLifecycleOwner().also { it.create() }
        composeView = view
        lifecycleOwner = owner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0
        }

        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.removeOnAttachStateChangeListener(this)
                owner.attachToView(view)
                owner.resume()
                view.setContent { FixedOverlay(notifications) }
            }

            override fun onViewDetachedFromWindow(v: android.view.View) = Unit
        })

        try {
            wm.addView(view, params)
        } catch (_: Throwable) {
            composeView = null
            lifecycleOwner = null
            try { owner.destroy() } catch (_: Throwable) {}
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class FixedNotification(
        val id: String,
        val index: Int,
        val icon: String?,
        val message: String,
        val messageColor: Color,
        val iconColor: Color,
        val borderColor: Color,
        val backgroundColor: Color,
        val shape: String
    )

    @Composable
    private fun FixedOverlay(items: Map<String, FixedNotification>) {
        val ordered = items.values
            .sortedWith(compareByDescending<FixedNotification> { it.index }.thenBy { it.id })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = java.text.SimpleDateFormat(
                    "HH:mm",
                    java.util.Locale.getDefault()
                ).format(java.util.Date()),
                color = Color.White,
                fontSize = 18.sp,
                maxLines = 1
            )

            Spacer(Modifier.width(10.dp))

            ordered.forEach { item ->
                FixedItem(item)
                Spacer(Modifier.width(6.dp))
            }
        }

        // The clock is derived exclusively from the Android TV's local clock.
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000L)
            }
        }
    }

    @Composable
    private fun FixedItem(item: FixedNotification) {
        val isCircle = item.shape == "circle"
        val shape = when (item.shape) {
            "circle" -> RoundedCornerShape(50)
            "rectangular" -> RoundedCornerShape(0.dp)
            else -> RoundedCornerShape(8.dp)
        }

        Row(
            modifier = Modifier
                .then(if (isCircle) Modifier.size(42.dp) else Modifier.height(42.dp))
                .clip(shape)
                .background(item.backgroundColor)
                .border(1.dp, item.borderColor, shape)
                .padding(
                    horizontal = if (isCircle) 0.dp else 9.dp,
                    vertical = 5.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            item.icon?.let { icon ->
                FixedIcon(icon)
                if (item.message.isNotBlank() && !isCircle) Spacer(Modifier.width(6.dp))
            }

            if (item.message.isNotBlank() && !isCircle) {
                Text(
                    text = item.message,
                    color = item.messageColor,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun FixedIcon(value: String) {
        val context = LocalContext.current
        val data = when {
            value.startsWith("mdi:") ->
                "https://api.iconify.design/${value.replace(":", "%3A")}.svg"
            else -> value
        }

        AsyncImage(
            model = ImageRequest.Builder(context).data(data).build(),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            contentScale = ContentScale.Fit
        )
    }
}
