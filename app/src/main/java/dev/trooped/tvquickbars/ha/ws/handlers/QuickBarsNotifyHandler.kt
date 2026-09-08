package dev.trooped.tvquickbars.ha.ws.handlers

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.notification.FixedNotificationController
import dev.trooped.tvquickbars.notification.NotificationSpec
import dev.trooped.tvquickbars.notification.toNotificationSpec
import org.json.JSONObject

class QuickBarsNotifyHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    private val TAG = "QuickBarsNotifyHandler"

    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "quickbars.notify"

    @OptIn(UnstableApi::class)
    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext()
        if (context == null) {
            Log.w(TAG, "Cannot handle notification event: Context is not available.")
            return
        }

        val data = event.optJSONObject("data") ?: return

        // Fixed notifications deliberately use the same already-open HA WebSocket and
        // quickbars.notify event subscription. Setting fixed=true switches only the UI
        // handling; no additional connection is created.
        if (data.optBoolean("fixed", false)) {
            FixedNotificationController.updateFromEvent(context, data)
            return
        }

        val targetId = data.optString("id", "")
        val myId = AppIdProvider.get(context) ?: ""
        if (targetId.isNotEmpty() && !targetId.equals(myId, ignoreCase = true)) {
            return
        }

        val cid = data.optString("cid", null)
        val spec: NotificationSpec = data.toNotificationSpec(cid)
        ctx.listener.onNotifyReceived(spec)
    }
}
