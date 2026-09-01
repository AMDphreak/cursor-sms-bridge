import express, { type NextFunction, type Request, type Response } from "express";
import { WebSocketServer, WebSocket } from "ws";
import type { Server } from "node:http";
import qrcode from "qrcode-terminal";
import { BridgeHub } from "./bridge-hub.js";
import type { BridgeConfig } from "./types.js";
import {
  createToken,
  getLanAddresses,
  loadInboundMessages,
  markMessageRead,
  normalizePhoneNumber,
  parseAllowedNumbers,
} from "./store.js";

function readConfig(): BridgeConfig {
  const allowedNumbers = parseAllowedNumbers(process.env.ALLOWED_NUMBERS);
  return {
    allowedNumbers,
    forwardAll: allowedNumbers.length === 0,
    webhookUrl: process.env.WEBHOOK_URL?.trim() || undefined,
  };
}

function requireApiKey(req: Request, res: Response, next: NextFunction): void {
  const configured = process.env.API_KEY?.trim();
  if (!configured) {
    res.status(500).json({ error: "API_KEY is not configured" });
    return;
  }

  const header = req.header("authorization");
  const token = header?.startsWith("Bearer ") ? header.slice(7) : req.header("x-api-key");
  if (token !== configured) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  next();
}

export function createBridgeServer(): {
  app: express.Express;
  attachWebSocket: (server: Server) => BridgeHub;
  config: BridgeConfig;
  pairingToken: string;
} {
  if (!process.env.PAIRING_TOKEN?.trim()) {
    process.env.PAIRING_TOKEN = createToken(18);
  }
  if (!process.env.API_KEY?.trim()) {
    process.env.API_KEY = createToken(18);
  }

  const config = readConfig();
  const hub = new BridgeHub(() => readConfig());
  const app = express();
  app.use(express.json({ limit: "1mb" }));

  app.get("/health", (_req, res) => {
    res.json({ ok: true });
  });

  app.get("/api/v1/status", requireApiKey, (_req, res) => {
    res.json({
      ...hub.getStatus(),
      config: readConfig(),
      pairingTokenSet: Boolean(process.env.PAIRING_TOKEN),
    });
  });

  app.get("/api/v1/pairing", requireApiKey, (req, res) => {
    const host = String(req.query.host ?? getLanAddresses()[0]);
    const port = Number(process.env.PORT ?? 8787);
    const token = process.env.PAIRING_TOKEN ?? "";
    const wsUrl = `ws://${host}:${port}/ws?token=${encodeURIComponent(token)}`;
    const qrPayload = JSON.stringify({ host, port, token, wsUrl });

    res.json({
      host,
      port,
      token,
      wsUrl,
      qrPayload,
      hint: "Scan qrPayload in the Android app or open the deep link.",
      deepLink: `cursorsms://pair?host=${encodeURIComponent(host)}&port=${port}&token=${encodeURIComponent(token)}`,
    });
  });

  app.get("/api/v1/messages", requireApiKey, (req, res) => {
    const from = req.query.from ? normalizePhoneNumber(String(req.query.from)) : undefined;
    const since = req.query.since ? Number(req.query.since) : undefined;
    const unreadOnly = req.query.unread === "true";

    let messages = loadInboundMessages();
    if (from) {
      messages = messages.filter(
        (message) => normalizePhoneNumber(message.from) === from,
      );
    }
    if (since) {
      messages = messages.filter((message) => message.receivedAt >= since);
    }
    if (unreadOnly) {
      messages = messages.filter((message) => !message.read);
    }

    res.json({ messages });
  });

  app.post("/api/v1/messages/:id/read", requireApiKey, (req, res) => {
    const id = String(req.params.id);
    const updated = markMessageRead(id);
    if (!updated) {
      res.status(404).json({ error: "Message not found" });
      return;
    }
    res.json({ message: updated });
  });

  app.post("/api/v1/messages/send", requireApiKey, (req, res) => {
    const to = normalizePhoneNumber(String(req.body?.to ?? ""));
    const body = String(req.body?.body ?? "").trim();
    if (!to || !body) {
      res.status(400).json({ error: "`to` and `body` are required" });
      return;
    }

    try {
      const request = hub.queueOutboundSms(to, body);
      res.status(202).json({ request });
    } catch (error) {
      res.status(503).json({
        error: error instanceof Error ? error.message : "Phone unavailable",
      });
    }
  });

  app.put("/api/v1/config", requireApiKey, (req, res) => {
    if (Array.isArray(req.body?.allowedNumbers)) {
      process.env.ALLOWED_NUMBERS = req.body.allowedNumbers
        .map((value: string) => normalizePhoneNumber(value))
        .filter(Boolean)
        .join(",");
    }
    if (typeof req.body?.webhookUrl === "string") {
      process.env.WEBHOOK_URL = req.body.webhookUrl.trim();
    }
    hub.pushConfigToPhone();
    res.json({ config: readConfig() });
  });

  app.post("/api/v1/webhook/test", requireApiKey, async (_req, res) => {
    const webhookUrl = readConfig().webhookUrl;
    if (!webhookUrl) {
      res.status(400).json({ error: "WEBHOOK_URL is not configured" });
      return;
    }

    const payload = {
      event: "sms.received",
      message: {
        id: "test-message",
        from: "+15551234567",
        body: "Test webhook from cursor-sms-bridge",
        receivedAt: new Date().toISOString(),
      },
    };

    const response = await fetch(webhookUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });

    res.json({
      delivered: response.ok,
      status: response.status,
    });
  });

  const attachWebSocket = (server: Server): BridgeHub => {
    const wss = new WebSocketServer({ server, path: "/ws" });
    wss.on("connection", (ws, req) => {
      const url = new URL(req.url ?? "/ws", "http://localhost");
      const queryToken = url.searchParams.get("token");
      if (queryToken && queryToken !== process.env.PAIRING_TOKEN) {
        ws.close(4401, "Unauthorized");
        return;
      }
      hub.attachPhone(ws);
    });
    return hub;
  };

  return {
    app,
    attachWebSocket,
    config,
    pairingToken: process.env.PAIRING_TOKEN ?? "",
  };
}

export function printStartupBanner(host: string, port: number, pairingToken: string, apiKey: string): void {
  const wsUrl = `ws://${host}:${port}/ws?token=${encodeURIComponent(pairingToken)}`;
  const qrPayload = JSON.stringify({ host, port, token: pairingToken, wsUrl });

  console.log("");
  console.log("cursor-sms-bridge desktop relay");
  console.log("================================");
  console.log(`HTTP:      http://${host}:${port}`);
  console.log(`WebSocket: ${wsUrl}`);
  console.log(`API key:   ${apiKey}`);
  console.log(`Pair token:${pairingToken}`);
  console.log("");
  console.log("Scan this QR payload in the Android app:");
  qrcode.generate(qrPayload, { small: true });
  console.log("");
}
