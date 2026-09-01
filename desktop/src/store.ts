import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";
import { networkInterfaces } from "node:os";
import type { BridgeConfig, InboundSms, OutboundSmsRequest } from "./types.js";

const DATA_DIR = path.resolve(process.cwd(), "data");
const MESSAGES_FILE = path.join(DATA_DIR, "messages.json");
const OUTBOUND_FILE = path.join(DATA_DIR, "outbound.json");

function ensureDataDir(): void {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

function readJsonFile<T>(filePath: string, fallback: T): T {
  ensureDataDir();
  if (!fs.existsSync(filePath)) {
    return fallback;
  }
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8")) as T;
  } catch {
    return fallback;
  }
}

function writeJsonFile<T>(filePath: string, value: T): void {
  ensureDataDir();
  fs.writeFileSync(filePath, JSON.stringify(value, null, 2), "utf8");
}

export function normalizePhoneNumber(value: string): string {
  const trimmed = value.trim();
  const digits = trimmed.replace(/[^\d+]/g, "");
  if (digits.startsWith("+")) {
    return `+${digits.slice(1).replace(/\D/g, "")}`;
  }
  return digits.replace(/\D/g, "");
}

export function isAllowedSender(from: string, config: BridgeConfig): boolean {
  if (config.forwardAll || config.allowedNumbers.length === 0) {
    return true;
  }
  const normalizedFrom = normalizePhoneNumber(from);
  return config.allowedNumbers.some(
    (allowed) => normalizePhoneNumber(allowed) === normalizedFrom,
  );
}

export function loadInboundMessages(): InboundSms[] {
  return readJsonFile<InboundSms[]>(MESSAGES_FILE, []);
}

export function saveInboundMessages(messages: InboundSms[]): void {
  writeJsonFile(MESSAGES_FILE, messages);
}

export function appendInboundMessage(message: InboundSms): void {
  const messages = loadInboundMessages();
  messages.unshift(message);
  saveInboundMessages(messages.slice(0, 500));
}

export function markMessageRead(id: string): InboundSms | undefined {
  const messages = loadInboundMessages();
  const target = messages.find((message) => message.id === id);
  if (target) {
    target.read = true;
    saveInboundMessages(messages);
  }
  return target;
}

export function loadOutboundRequests(): OutboundSmsRequest[] {
  return readJsonFile<OutboundSmsRequest[]>(OUTBOUND_FILE, []);
}

export function saveOutboundRequests(requests: OutboundSmsRequest[]): void {
  writeJsonFile(OUTBOUND_FILE, requests);
}

export function upsertOutboundRequest(request: OutboundSmsRequest): void {
  const requests = loadOutboundRequests();
  const index = requests.findIndex((item) => item.requestId === request.requestId);
  if (index >= 0) {
    requests[index] = request;
  } else {
    requests.unshift(request);
  }
  saveOutboundRequests(requests.slice(0, 200));
}

export function createToken(bytes = 24): string {
  return randomBytes(bytes).toString("base64url");
}

export function parseAllowedNumbers(raw?: string): string[] {
  if (!raw?.trim()) {
    return [];
  }
  return raw
    .split(",")
    .map((value) => normalizePhoneNumber(value))
    .filter(Boolean);
}

export function getLanAddresses(): string[] {
  const interfaces = networkInterfaces();
  const addresses: string[] = [];
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries ?? []) {
      if (entry.family === "IPv4" && !entry.internal) {
        addresses.push(entry.address);
      }
    }
  }
  return addresses.length > 0 ? addresses : ["127.0.0.1"];
}
