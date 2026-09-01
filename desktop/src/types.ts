export type InboundSms = {
  id: string;
  from: string;
  body: string;
  receivedAt: number;
  read: boolean;
};

export type OutboundSmsRequest = {
  requestId: string;
  to: string;
  body: string;
  queuedAt: number;
  status: "queued" | "sent" | "failed";
  error?: string;
};

export type BridgeConfig = {
  allowedNumbers: string[];
  forwardAll: boolean;
  webhookUrl?: string;
};

export type PhoneAuthMessage = {
  type: "auth";
  token: string;
  deviceName?: string;
};

export type PhoneSmsInMessage = {
  type: "sms_in";
  id: string;
  from: string;
  body: string;
  receivedAt: number;
};

export type PhoneSmsOutAckMessage = {
  type: "sms_out_ack";
  requestId: string;
  success: boolean;
  error?: string | null;
};

export type PhonePingMessage = {
  type: "ping";
  at: number;
};

export type DesktopAuthOkMessage = {
  type: "auth_ok";
  deviceId: string;
};

export type DesktopSmsOutMessage = {
  type: "sms_out";
  requestId: string;
  to: string;
  body: string;
};

export type DesktopConfigMessage = {
  type: "config";
  allowedNumbers: string[];
  forwardAll: boolean;
};

export type DesktopPongMessage = {
  type: "pong";
  at: number;
};

export type DesktopErrorMessage = {
  type: "error";
  message: string;
};

export type PhoneToDesktopMessage =
  | PhoneAuthMessage
  | PhoneSmsInMessage
  | PhoneSmsOutAckMessage
  | PhonePingMessage;

export type DesktopToPhoneMessage =
  | DesktopAuthOkMessage
  | DesktopSmsOutMessage
  | DesktopConfigMessage
  | DesktopPongMessage
  | DesktopErrorMessage;

export type WebhookPayload = {
  event: "sms.received";
  message: {
    id: string;
    from: string;
    body: string;
    receivedAt: string;
  };
};
