package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.notification.FixedNotificationController
import org.json.JSONObject

/** Handles fixed notifications over the existing QuickBars Home Assistant WebSocket. */
class QuickBarsFixedNotifyHandler : WsHandler {
    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "quickbars.notify_fixed"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: return
        val data = event.optJSONObject("data") ?: return

        // Preserve the same TV-targeting behavior as normal QuickBars notifications.
        val targetId = data.optString("id", "")
        val myId = AppIdProvider.get(context) ?: ""
        val explicitTarget = data.optString("target_id", "")
        if (explicitTarget.isNotEmpty() && !explicitTarget.equals(myId, ignoreCase = true)) return

        // The fixed notification id is intentionally separate from target_id.
        // If callers use target_id, it is consumed only for routing and is not rendered.
        if (data.has("target_id")) data.remove("target_id")
        if (targetId.isNotEmpty() && targetId.equals(myId, ignoreCase = true) && data.optString("message", "").isBlank()) {
            // Do not special-case a legitimate empty-message indicator; continue below.
        }

        FixedNotificationController.updateFromEvent(context, data)
    }
}
