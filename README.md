# cursor-sms-bridge

Android + Windows companion apps that relay SMS over your LAN so Cursor automations can read and reply through your real phone number.

## What you get

- **Android app** — listens for SMS, forwards filtered messages to your PC, sends outbound texts on command.
- **Desktop relay** — WebSocket bridge + REST API + optional webhook for Cursor automations.
- **QR pairing** — desktop prints a QR code; phone scans it on the same Wi‑Fi network.

iOS is not included yet. The protocol is documented in [`protocol/messages.md`](protocol/messages.md) so an iOS companion can be added later.

## Quick start

### 1. Desktop relay (Windows / macOS / Linux)

```powershell
cd desktop
copy .env.example .env
# Edit .env: set API_KEY and optional ALLOWED_NUMBERS / WEBHOOK_URL
pnpm install
pnpm dev
```

The terminal prints:

- LAN WebSocket URL
- API key
- Pairing token
- QR code (JSON payload)

Keep this running while the phone is connected.

### 2. Android app

Requirements: Android Studio, Android SDK, JDK 17+.

```powershell
cd android
# Open in Android Studio, or:
./gradlew :app:assembleDebug
```

Install the debug APK on your phone. Grant SMS and notification permissions.

1. Tap **Scan desktop QR** and scan the terminal QR (or paste the JSON payload manually).
2. Optionally set an allowlist like `+15551234567` (comma-separated). Empty = forward all senders.
3. The foreground service keeps the WebSocket connected.

Phone and PC must be on the same LAN. Windows Firewall may prompt to allow port **8787**.

### 3. Cursor automation hook

Configure a webhook on the desktop relay:

```powershell
curl -X PUT http://127.0.0.1:8787/api/v1/config `
  -H "Authorization: Bearer YOUR_API_KEY" `
  -H "Content-Type: application/json" `
  -d '{"webhookUrl":"https://your-cursor-automation-webhook"}'
```

When a filtered SMS arrives, the relay POSTs:

```json
{
  "event": "sms.received",
  "message": {
    "id": "…",
    "from": "+15551234567",
    "body": "Hello",
    "receivedAt": "2026-09-01T12:00:00.000Z"
  }
}
```

Reply from an automation or script:

```powershell
curl -X POST http://127.0.0.1:8787/api/v1/messages/send `
  -H "Authorization: Bearer YOUR_API_KEY" `
  -H "Content-Type: application/json" `
  -d '{"to":"+15551234567","body":"On my way"}'
```

See [`docs/cursor-automation.md`](docs/cursor-automation.md) for a full Cursor wiring example.

## REST API

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Liveness |
| `GET /api/v1/status` | Connection + config |
| `GET /api/v1/pairing` | Pairing QR payload |
| `GET /api/v1/messages` | Stored inbound messages |
| `POST /api/v1/messages/send` | Send SMS via phone |
| `PUT /api/v1/config` | Allowlist + webhook |

All `/api/v1/*` routes require `Authorization: Bearer <API_KEY>`.

## Architecture

```
Android phone ──WebSocket──► Desktop relay ──webhook──► Cursor automation
                                ▲
                                └── REST (send / list messages)
```

## Security notes

- LAN-only by default (`ws://` cleartext on local network). Do not expose port 8787 to the internet without TLS and stronger auth.
- Treat `API_KEY` and pairing token like passwords.
- Auto-replies can misfire. Start with an allowlist for one trusted contact.

## License

Personal tooling — adjust before publishing to Play Store (SMS permission review required for companion apps).
