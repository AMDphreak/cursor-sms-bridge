import http from "node:http";
import fs from "node:fs";
import { createBridgeServer, printStartupBanner } from "./server.js";
import { getLanAddresses } from "./store.js";

function loadEnvFile(): void {
  const envPath = new URL("../.env", import.meta.url);
  try {
    if (!fs.existsSync(envPath)) {
      return;
    }
    const content = fs.readFileSync(envPath, "utf8");
    for (const line of content.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) {
        continue;
      }
      const index = trimmed.indexOf("=");
      if (index <= 0) {
        continue;
      }
      const key = trimmed.slice(0, index).trim();
      const value = trimmed.slice(index + 1).trim();
      if (!process.env[key]) {
        process.env[key] = value;
      }
    }
  } catch {
    // Optional .env
  }
}

loadEnvFile();

const port = Number(process.env.PORT ?? 8787);
const { app, attachWebSocket, pairingToken } = createBridgeServer();
const server = http.createServer(app);
attachWebSocket(server);

server.listen(port, "0.0.0.0", () => {
  const host = getLanAddresses()[0] ?? "127.0.0.1";
  printStartupBanner(host, port, pairingToken, process.env.API_KEY ?? "");
});
