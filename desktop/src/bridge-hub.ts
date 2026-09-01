import { WebSocket } from "ws";
import { v4 as uuidv4 } from "uuid";
import type {
  BridgeConfig,
  DesktopToPhoneMessage,
  InboundSms,
  OutboundSmsRequest,
  PhoneToDesktopMessage,
  WebhookPayload,
} from "./types.js";
import {
  appendInboundMessage,
  isAllowedSender,
  upsertOutboundRequest,
} from "./store.js";

export type PhoneSession = {
  ws: WebSocket;
  deviceId: string;
  deviceName?: string;
  connectedAt: number;
  authenticated: boolean;
};

export class BridgeHub {
  private session: PhoneSession | null = null;
  private pendingOutbound = new Map<string, OutboundSmsRequest>();

  constructor(private getConfig: () => BridgeConfig) {}

  getStatus() {
    return {
      phoneConnected: this.session?.authenticated === true,
      deviceId: this.session?.deviceId ?? null,
      deviceName: this.session?.deviceName ?? null,
      connectedAt: this.session?.connectedAt ?? null,
      pendingOutbound: this.pendingOutbound.size,
    };
  }

  attachPhone(ws: WebSocket): void {
    if (this.session?.ws.readyState === WebSocket.OPEN) {
      this.session.ws.close(4000, "Replaced by new connection");
    }

    const deviceId = uuidv4();
    this.session = {
      ws,
      deviceId,
      connectedAt: Date.now(),
      authenticated: false,
    };

    ws.on("message", (raw) => {
      this.handlePhoneMessage(String(raw));
    });

    ws.on("close", () => {
      if (this.session?.ws === ws) {
        this.session = null;
      }
    });
  }

  pushConfigToPhone(): void {
    const session = this.session;
    if (!session?.authenticated) {
      return;
    }
    const config = this.getConfig();
    this.sendToPhone(session, {
      type: "config",
      allowedNumbers: config.allowedNumbers,
      forwardAll: config.forwardAll,
    });
  }

  queueOutboundSms(to: string, body: string): OutboundSmsRequest {
    const session = this.session;
    if (!session?.authenticated) {
      throw new Error("Phone is not connected");
    }

    const request: OutboundSmsRequest = {
      requestId: uuidv4(),
      to,
      body,
      queuedAt: Date.now(),
      status: "queued",
    };

    this.pendingOutbound.set(request.requestId, request);
    upsertOutboundRequest(request);

    this.sendToPhone(session, {
      type: "sms_out",
      requestId: request.requestId,
      to: request.to,
      body: request.body,
    });

    return request;
  }

  private handlePhoneMessage(raw: string): void {
    let message: PhoneToDesktopMessage;
    try {
      message = JSON.parse(raw) as PhoneToDesktopMessage;
    } catch {
      this.sendError("Invalid JSON");
      return;
    }

    switch (message.type) {
      case "auth":
        this.handleAuth(message.token, message.deviceName);
        return;
      case "sms_in":
        void this.handleInboundSms(message);
        return;
      case "sms_out_ack":
        this.handleOutboundAck(message.requestId, message.success, message.error ?? undefined);
        return;
      case "ping":
        this.replyPong(message.at);
        return;
      default:
        this.sendError(`Unsupported message type: ${(message as { type: string }).type}`);
    }
  }

  private handleAuth(token: string, deviceName?: string): void {
    const session = this.session;
    if (!session) {
      return;
    }

    const expected = process.env.PAIRING_TOKEN;
    if (!expected || token !== expected) {
      this.sendToPhone(session, { type: "error", message: "Invalid pairing token" });
      session.ws.close(4401, "Unauthorized");
      this.session = null;
      return;
    }

    session.authenticated = true;
    session.deviceName = deviceName;
    this.sendToPhone(session, { type: "auth_ok", deviceId: session.deviceId });
    this.pushConfigToPhone();
  }

  private async handleInboundSms(message: {
    id: string;
    from: string;
    body: string;
    receivedAt: number;
  }): Promise<void> {
    const config = this.getConfig();
    if (!isAllowedSender(message.from, config)) {
      return;
    }

    const inbound: InboundSms = {
      id: message.id,
      from: message.from,
      body: message.body,
      receivedAt: message.receivedAt,
      read: false,
    };
    appendInboundMessage(inbound);

    const webhookUrl = config.webhookUrl;
    if (webhookUrl) {
      const payload: WebhookPayload = {
        event: "sms.received",
        message: {
          id: inbound.id,
          from: inbound.from,
          body: inbound.body,
          receivedAt: new Date(inbound.receivedAt).toISOString(),
        },
      };
      try {
        await fetch(webhookUrl, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
      } catch (error) {
        console.error("Webhook delivery failed:", error);
      }
    }
  }

  private handleOutboundAck(
    requestId: string,
    success: boolean,
    error?: string,
  ): void {
    const pending = this.pendingOutbound.get(requestId);
    if (!pending) {
      return;
    }

    pending.status = success ? "sent" : "failed";
    pending.error = error;
    upsertOutboundRequest(pending);
    this.pendingOutbound.delete(requestId);
  }

  private replyPong(at: number): void {
    const session = this.session;
    if (!session?.authenticated) {
      return;
    }
    this.sendToPhone(session, { type: "pong", at });
  }

  private sendError(message: string): void {
    const session = this.session;
    if (!session) {
      return;
    }
    this.sendToPhone(session, { type: "error", message });
  }

  private sendToPhone(session: PhoneSession, message: DesktopToPhoneMessage): void {
    if (session.ws.readyState === WebSocket.OPEN) {
      session.ws.send(JSON.stringify(message));
    }
  }
}
