# QuickBars Accessibility Watchdog for Home Assistant

Android TV and Google TV devices often aggressively terminate background accessibility services during deep sleep or RAM management cycles. This results in two common failure modes: either the system explicitly toggles the accessibility service off, or the service silently crashes in the background while the TV's settings menu deceptively still shows it as "Enabled."

This repository provides a complete Home Assistant watchdog solution that handles both scenarios:

* **Verifies active system bindings** via ADB rather than trusting the Settings menu, allowing it to detect both explicitly disabled toggles and silent background crashes.
* **Maintains a reliable sensor** in Home Assistant showing whether QuickBars is actively `running` or `dead`.
* **Automatically recovers the service** via a clean ADB restart sequence that works regardless of how the service failed, without disrupting other accessibility tools (like TalkBack or Button Mapper).
* **Sends mobile alerts** if the service goes offline.

## Prerequisites

**Enable Network Debugging on your TV:**
1. Go to **Settings > Device Preferences > About** on your TV and tap **Build** 7 times to unlock Developer Options.
2. Go to **Developer Options** and enable **Network Debugging** (ADB over TCP/IP).

**Connect TV to Home Assistant:**
1. Install the official **Android Debug Bridge** integration in Home Assistant.
2. Note your TV entity ID (e.g., `media_player.living_room_tv`).

## Setup Guide

### Step 1: Create the Polling Automation

This automation pings the TV every minute while it is turned on to verify that Android's accessibility system server maintains an active Binder connection labeled `QuickBars`. Running this first populates the response attribute so downstream helpers immediately work.

1. Go to **Settings > Automations & scenes** and click **Create Automation**.
2. Click the three dots (`⋮`) in the top-right corner and select **Edit in YAML**.
3. Paste the following configuration, replacing `media_player.YOUR_TV_ENTITY` with the ADB entity from earlier:

```yaml
alias: "Watchdog: Poll QuickBars Status"
description: "Pings the TV accessibility system server every minute to verify active bindings"
mode: single
trigger:
  - platform: time_pattern
    minutes: "/1"
condition:
  - condition: not
    conditions:
      - condition: state
        entity_id: media_player.YOUR_TV_ENTITY
        state:
          - "off"
          - "unavailable"
          - "unknown"
action:
  - action: androidtv.adb_command
    target:
      entity_id: media_player.YOUR_TV_ENTITY
    data:
      command: dumpsys accessibility | grep -A 3 'Bound services:' | grep -q 'label=QuickBars' && echo 'running' || echo 'dead'
```
4. Save the automation, click the three dots (`⋮`), and select **Run** once to populate the status immediately.

---

### Step 2: Create the Status Helper

The polling automation writes its output into the TV entity's `adb_response` attribute. This Template Sensor isolates that response and discards unrelated background ADB noise.

1. In Home Assistant, go to **Settings > Devices & Services > Helpers**.
2. Click **+ Create Helper** and select **Template**, then **Template a sensor**.
3. Configure the helper:
   * **Name:** `QuickBars Status`
   * **State template:** Paste the following snippet (replace `media_player.YOUR_TV_ENTITY` with your TV's real entity ID):

```jinja2
{% set response = state_attr('media_player.YOUR_TV_ENTITY', 'adb_response') %}
{% if response in ['running', 'dead'] %}
  {{ response }}
{% else %}
  {{ this.state | default('unknown') }}
{% endif %}
```
4. Click **Submit**. This creates `sensor.quickbars_status`, which will immediately display `running`.

---

### Step 3: Create the Restart Script

This script safely strips `QuickBars` out of the Android TV's enabled services string, waits 2 seconds, and then appends it back to rebind the service without disabling any other accessibility apps.

1. Go to **Settings > Automations & scenes > Scripts** and click **Add Script**.
2. Click the three dots (`⋮`) in the top-right corner and select **Edit in YAML**.
3. Paste the following configuration, replacing `media_player.YOUR_TV_ENTITY`:

```yaml
alias: "Restart QuickBars Accessibility Service"
sequence:
  - action: androidtv.adb_command
    target:
      entity_id: media_player.YOUR_TV_ENTITY
    data:
      command: >-
        TARGET="dev.trooped.tvquickbars/.services.QuickBarService";
        CURR=$(settings get secure enabled_accessibility_services);
        TEMP=${CURR//$TARGET/}; TEMP=${TEMP//::/:}; TEMP=${TEMP#:};
        TEMP=${TEMP%:}; settings put secure enabled_accessibility_services "$TEMP"
  - delay:
      seconds: 2
  - action: androidtv.adb_command
    target:
      entity_id: media_player.YOUR_TV_ENTITY
    data:
      command: >-
        TARGET="dev.trooped.tvquickbars/.services.QuickBarService";
        CURR=$(settings get secure enabled_accessibility_services);
        if ! echo "$CURR" | grep -q "$TARGET"; then
          if [ -z "$CURR" ] || [ "$CURR" = "null" ]; then
            NEW="$TARGET";
          else
            NEW="$CURR:$TARGET";
          fi;
          settings put secure enabled_accessibility_services "$NEW";
        fi
mode: single
icon: mdi:restart
```
4. Save the script.

---

### Step 4: Create the Auto-Heal & Alert Automation

This automation monitors `sensor.quickbars_status`. If the service stays `dead` for 2 minutes (preventing false alarms during app launches or sleep transitions), Home Assistant runs the restart script and sends an alert.

1. Go to **Settings > Automations & scenes** and click **Create Automation**.
2. Click the three dots (`⋮`) in the top-right corner and select **Edit in YAML**.
3. Paste the following configuration (replace `media_player.YOUR_TV_ENTITY` and adjust `notify.notify` if targeting a specific device):

```yaml
alias: "Watchdog: Auto-Restart QuickBars & Notify"
description: "Automatically restarts QuickBars and sends an alert if the service stays dead for 2 minutes"
mode: single
trigger:
  - platform: state
    entity_id: sensor.quickbars_status
    to: "dead"
    for:
      minutes: 2
condition:
  - condition: state
    entity_id: media_player.YOUR_TV_ENTITY
    state: "on"
action:
  - action: script.restart_quickbars_accessibility_service
  - action: notify.notify
    data:
      title: "QuickBars Watchdog"
      message: "QuickBars accessibility service crashed on the TV and was automatically restarted."
```
4. Save the automation.

## How to Test

1. Navigate to **Settings > Device Preferences > Accessibility** on your TV and manually toggle QuickBars **Off**.
2. Wait 2 minutes.
3. Check Home Assistant:
   * `sensor.quickbars_status` will change to `dead`.
   * The restart automation will fire.
   * QuickBars will turn back **On** automatically on your TV, and the sensor will return to `running`.
