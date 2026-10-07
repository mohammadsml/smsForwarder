# SMS Forwarder (Android)

<p align="center">
  <img src="app/src/main/res/drawable/ic_stat.xml" width="96" height="96" alt="SMS Forwarder Logo" />
</p>

<p align="center">
  <b>A lightweight, durable Android background forwarder for SMS and HTTP Webhooks.</b><br>
  Forward SMS messages from specific senders (e.g., Banks, OTPs, Services) to other phone numbers via SMS and/or to remote web servers via JSON HTTP POST.
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#http-webhook-specification">HTTP Webhook</a> •
  <a href="#message-template">Templates</a> •
  <a href="#installation--setup">Setup</a> •
  <a href="#building-from-source">Build</a> •
  <a href="#license">License</a>
</p>

---

## ✨ Features

- **🔀 Dual Independent Forwarding Channels**:
  - **Forward to SMS**: Dispatches messages to one or multiple destination phone numbers.
  - **Forward to HTTP POST (JSON)**: Posts messages to one or multiple custom webhook endpoints (Workers).
  - Both channels can be toggled ON or OFF independently.
- **⚡ Multiple HTTP Workers / Endpoints**:
  - Add unlimited server URLs (e.g. `https://api.yourdomain.com/sms` or local IP endpoints like `http://192.168.1.100:8000/webhook`).
  - Supports cleartext HTTP (`http://`) as well as secure HTTPS (`https://`).
- **📝 Message Template Engine**:
  - Easily customize the forwarded text with prefix and suffix around the `{text}` placeholder (e.g., `[Bank Alert] {text} - AutoForwarded`).
- **🔍 Dedicated In-App Traffic & Event Inspector (`LogActivity`)**:
  - Inspect exact outgoing HTTP requests and incoming server response codes and response bodies in real-time.
  - View system logs, copy full logs to clipboard, or clear with a single tap.
- **🛡️ Durable Outbound Queues (Crash & Offline Resistant)**:
  - Separate durable queues for both SMS and HTTP with automated exponential backoff retry (30s, 1m, 2m ... up to 15m).
  - Messages are only dequeued upon receiving a confirmed 2xx HTTP response or SMS delivery receipt.
- **🔋 Deep Background Persistence**:
  - Android 14+ compliant `remoteMessaging` foreground service with a lightweight ongoing silent notification.
  - 15-minute Watchdog alarm to revive the service and drain queues.
  - Recovers automatically upon device reboot (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT`, `MY_PACKAGE_REPLACED`).
  - Built-in one-tap prompt to exempt the app from battery optimizations (Doze mode).
- **🪶 Zero External Dependencies**:
  - Built strictly on Android standard SDK (`java.net.HttpURLConnection`, `org.json.JSONObject`).
  - No bloated libraries, no third-party tracking, ultra-fast compilation and minimal battery/RAM footprint.

---

## 🌐 HTTP Webhook Specification

When a matching SMS is received, an asynchronous HTTP POST request is sent to each configured worker endpoint.

### Request Headers
```http
POST /your/endpoint HTTP/1.1
Host: your-server.com
Content-Type: application/json; charset=utf-8
Accept: application/json, text/plain, */*
User-Agent: SmsForwarder/1.0
```

### Request JSON Payload
```json
{
  "from": "TejaratBank",
  "text": "[Alert] Your account was credited with 1,000,000 Rials",
  "rawText": "Your account was credited with 1,000,000 Rials",
  "timestamp": 1728325491000,
  "date": "2026-10-07T17:44:47Z"
}
```

| Field | Type | Description |
|---|---|---|
| `from` | string | Originating SMS address or sender header (e.g. `TejaratBank`, `3000777`). |
| `text` | string | The message body formatted according to your custom template. |
| `rawText` | string | The original, untouched incoming SMS text. |
| `timestamp` | number | Epoch timestamp in milliseconds. |
| `date` | string | ISO-8601 UTC formatted date string. |

### Expected Server Response
- **HTTP 200..299**: Marked as successful and removed from the queue.
- **Any other status code or timeout**: Message remains safely in the queue and retries with backoff.

### Quick Server Receiver Examples

<details>
<summary><b>Node.js (Express)</b></summary>

```javascript
const express = require('express');
const app = express();
app.use(express.json());

app.post('/api/sms', (req, res) => {
  const { from, text, rawText, timestamp } = req.body;
  console.log(`[SMS from ${from}]: ${text}`);
  res.status(200).json({ status: 'ok' });
});

app.listen(3000, () => console.log('Listening on port 3000'));
```
</details>

<details>
<summary><b>Python (FastAPI)</b></summary>

```python
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()

class SmsPayload(BaseModel):
    from_: str = Field(alias="from")
    text: str
    rawText: str
    timestamp: int
    date: str

@app.post("/api/sms")
async def receive_sms(payload: SmsPayload):
    print(f"Received SMS from {payload.from_}: {payload.text}")
    return {"status": "success"}
```
</details>

---

## 📝 Message Template Engine

In the **Forwarded Text Template** card, you can configure how the message text is composed before forwarding:

- Use `{text}` wherever the incoming SMS body should appear.
- **Examples**:
  - `[Alert] {text}` &rarr; prefixes `[Alert]` before the SMS.
  - `{text}\n-- Forwarded by Phone` &rarr; adds a footer to the SMS.
  - `[Bank] {text} [End]` &rarr; surrounds the SMS with prefix and suffix.
- If left as `{text}`, the message is forwarded as-is.

---

## 🚀 Installation & Setup

1. **Build or Download the APK**: Install `app-debug.apk` directly on your Android phone.
2. **Grant Permissions**:
   - Open the app and tap **Grant Permissions**.
   - Allow `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS` (and `POST_NOTIFICATIONS` on Android 13+).
3. **Configure Filters & Destinations**:
   - **Sources**: Add sender names or numbers you want to forward (e.g. `tejarat`, `3000777`). Leave blank to forward all incoming SMS.
   - **SMS Destinations**: Enter recipient phone numbers (e.g. `0912xxxxxxx`).
   - **HTTP Workers**: Enter webhook URLs (e.g. `https://example.com/api/sms`).
4. **Disable Battery Optimization**:
   - Tap **Disable Battery Optimization** in the app and allow the prompt. This prevents Android Doze from putting the forwarder to sleep.
5. **Enable Forwarding**:
   - Turn ON **Forward to SMS** and/or **Forward to HTTP POST (JSON)**.

---

## 🔋 OEM-Specific Background Settings

Certain Android manufacturers use aggressive background task killers. To ensure 24/7 reliability:

- **Xiaomi / POCO (MIUI / HyperOS)**:
  - Settings &rarr; Apps &rarr; Manage apps &rarr; **SMS Forwarder** &rarr; Enable **Autostart**.
  - Battery saver &rarr; Select **No restrictions**.
- **Samsung (OneUI)**:
  - Settings &rarr; Battery &rarr; Background usage limits &rarr; **Never sleeping apps** &rarr; Add **SMS Forwarder**.
- **Huawei / Honor**:
  - Settings &rarr; Battery &rarr; App launch &rarr; **SMS Forwarder** &rarr; Manage manually &rarr; Enable all 3 switches.
- **Oppo / Realme / Vivo**:
  - App info &rarr; Battery usage &rarr; Enable **Allow background activity** & **Allow auto-start**.

---

## 🔨 Building from Source

### Requirements
- JDK 17+
- Android SDK (`platforms;android-34` and `build-tools;34.0.0`)

### Command Line
```bash
# Set your SDK directory (if not already set)
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test
```

Compiled APK location:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📂 Project Structure

```text
app/src/main/java/com/freebuff/tejaratsmsfwd/
├── MainActivity.kt        # Primary UI: switches, templates, workers, sources, destinations
├── LogActivity.kt         # Dedicated Inspector: full HTTP JSON traffic & event logs
├── SmsReceiver.kt         # BroadcastReceiver for SMS_RECEIVED + filter + enqueue
├── Sender.kt              # SMS dispatcher (multipart handling & number normalization)
├── HttpSender.kt          # HTTP POST dispatcher (background thread, JSON, error handling)
├── SentReceiver.kt        # SMS Delivery receipt callback (ack/retry)
├── ForwardService.kt      # Foreground service (remoteMessaging) + ongoing notification
├── WatchdogReceiver.kt    # 15-minute keep-alive alarm & queue flushing
├── BootReceiver.kt        # Auto-start on reboot or package update
├── Scheduler.kt           # AlarmManager scheduler & safe service launcher
├── Store.kt               # Central synchronized state, queues, settings & logs
└── App.kt                 # Application class & Notification Channel setup

app/src/test/.../
└── ForwarderLogicTest.kt  # Robolectric tests for filters, queues, templates & workers
```

---

## 👤 Author & Credits

Developed by **[mohammadsml](https://github.com/mohammadsml)**.

---

## 📄 License

This project is open-source and licensed under the [MIT License](LICENSE).
Feel free to use, modify, and distribute it for personal or commercial needs.
