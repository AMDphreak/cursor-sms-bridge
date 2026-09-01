# Cursor automation wiring

## Flow

1. Desktop relay receives SMS from Android over WebSocket.
2. Relay stores the message and POSTs to your Cursor automation webhook.
3. Automation prompt uses the message body + sender to draft a reply.
4. Automation calls `POST /api/v1/messages/send` on the relay.

## Webhook trigger (Cursor Automation)

Create an automation with trigger **On an incoming HTTP webhook**.

After save, copy the webhook URL into the relay:

```powershell
curl -X PUT http://127.0.0.1:8787/api/v1/config `
  -H "Authorization: Bearer YOUR_API_KEY" `
  -H "Content-Type: application/json" `
  -d '{"webhookUrl":"PASTE_CURSOR_WEBHOOK_URL"}'
```

Suggested automation prompt:

> You receive JSON for an inbound SMS in `sms.received` events. Read `message.from` and `message.body`. Draft a concise, friendly reply appropriate for that sender. Then call the desktop relay send API with the same `from` number as `to` and your reply as `body`. Use the API key from secrets. Do not reply if the message looks like a one-time code or password reset unless explicitly configured to.

Enable the **MCP** or shell tool your automation needs to call the local relay, or use a small local script the automation invokes.

## Local send script (PowerShell)

```powershell
param(
  [Parameter(Mandatory)] [string] $To,
  [Parameter(Mandatory)] [string] $Body,
  [string] $ApiKey = $env:CURSOR_SMS_BRIDGE_API_KEY,
  [string] $BaseUrl = "http://127.0.0.1:8787"
)

Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/messages/send" `
  -Headers @{ Authorization = "Bearer $ApiKey" } `
  -ContentType "application/json" `
  -Body (@{ to = $To; body = $Body } | ConvertTo-Json)
```

Set `$env:CURSOR_SMS_BRIDGE_API_KEY` to match `desktop/.env`.

## Polling alternative

If you prefer not to use webhooks, schedule an automation every few minutes:

1. `GET /api/v1/messages?unread=true`
2. Process each message
3. `POST /api/v1/messages/{id}/read`

Webhooks are lower latency and simpler for auto-reply.

## Test webhook delivery

```powershell
curl -X POST http://127.0.0.1:8787/api/v1/webhook/test `
  -H "Authorization: Bearer YOUR_API_KEY"
```
