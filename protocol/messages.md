# Wire protocol

JSON messages over a WebSocket between the Android companion and the desktop relay.

## Connection

1. Desktop relay starts and exposes `ws://<lan-ip>:8787/ws`.
2. Phone connects with query params: `?token=<pairing-token>`.
3. First message from phone must be `{ "type": "auth", "token": "<same-token>" }`.
4. Desktop replies `{ "type": "auth_ok", "deviceId": "<uuid>" }` or `{ "type": "error", "message": "..." }`.

## Phone → desktop

### `sms_in`

```json
{
  "type": "sms_in",
  "id": "uuid",
  "from": "+15551234567",
  "body": "Hello",
  "receivedAt": 1710000000000
}
```

### `sms_out_ack`

```json
{
  "type": "sms_out_ack",
  "requestId": "uuid",
  "success": true,
  "error": null
}
```

### `ping`

```json
{ "type": "ping", "at": 1710000000000 }
```

## Desktop → phone

### `sms_out`

```json
{
  "type": "sms_out",
  "requestId": "uuid",
  "to": "+15551234567",
  "body": "Reply text"
}
```

### `config`

Updates runtime filters on the phone.

```json
{
  "type": "config",
  "allowedNumbers": ["+15551234567"],
  "forwardAll": false
}
```

### `pong`

```json
{ "type": "pong", "at": 1710000000000 }
```

## REST API (desktop)

All REST calls require header `Authorization: Bearer <api-key>` unless noted.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness (no auth) |
| GET | `/api/v1/status` | Phone connection + pairing state |
| GET | `/api/v1/pairing` | Pairing URL + QR payload |
| GET | `/api/v1/messages` | List stored messages (`?from=`, `?since=`, `?unread=`) |
| POST | `/api/v1/messages/send` | Queue outbound SMS via phone |
| PUT | `/api/v1/config` | Update allowlist + webhook URL |
| POST | `/api/v1/webhook/test` | Fire test payload to configured webhook |

### Send message

```json
POST /api/v1/messages/send
{
  "to": "+15551234567",
  "body": "On my way"
}
```

### Webhook payload (desktop → Cursor automation)

When a filtered inbound SMS arrives:

```json
{
  "event": "sms.received",
  "message": {
    "id": "uuid",
    "from": "+15551234567",
    "body": "Hello",
    "receivedAt": "2026-09-01T12:00:00.000Z"
  }
}
```

Cursor automations can call back with `POST /api/v1/messages/send`.
