/**
 * RasFocus Relay Server (Node.js)
 * ================================
 * Bridges WebSocket connections between PC and Phone using a shared code.
 *
 * Deploy on any VPS / cloud VM:
 *   node relay_server.js
 *
 * URL format:
 *   wss://relay.rasfocus.com/relay/<code>?role=host    (PC connects)
 *   wss://relay.rasfocus.com/relay/<code>?role=client  (Phone connects)
 *   wss://relay.rasfocus.com/relay/<code>              (auto: first=host, second=client)
 *
 * Flow:
 *   1. PC generates code → connects to relay as host
 *   2. Phone types code → connects to relay as client
 *   3. Relay pipes all messages bidirectionally
 *   4. Either side disconnects → relay cleans up both
 *
 * Install:
 *   npm init -y && npm install ws
 *   node relay_server.js
 *
 * With SSL (required for wss://):
 *   Use nginx/caddy as reverse proxy with Let's Encrypt cert,
 *   or pass cert/key to https.createServer() below.
 *
 * Alternatively, run behind Cloudflare Tunnel (free SSL):
 *   cloudflared tunnel --url http://localhost:9226
 */

"use strict";

const { WebSocketServer, WebSocket } = require("ws");
const http  = require("http");
const url   = require("url");

const PORT = process.env.PORT || 9226;

// ── Session store ─────────────────────────────────────────────────
// code → { host: WebSocket | null, client: WebSocket | null, createdAt: Date }
const sessions = new Map();

// Cleanup stale sessions older than 30 minutes
setInterval(() => {
    const cutoff = Date.now() - 30 * 60 * 1000;
    for (const [code, sess] of sessions) {
        if (sess.createdAt < cutoff) {
            try { sess.host?.close();   } catch (_) {}
            try { sess.client?.close(); } catch (_) {}
            sessions.delete(code);
            console.log(`[relay] Cleaned stale session ${code}`);
        }
    }
}, 60_000);

// ── HTTP server (health check) ────────────────────────────────────
const server = http.createServer((req, res) => {
    if (req.url === "/health") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({
            status: "ok",
            sessions: sessions.size,
            uptime: process.uptime()
        }));
    } else {
        res.writeHead(404);
        res.end("RasFocus Relay Server");
    }
});

// ── WebSocket server ──────────────────────────────────────────────
const wss = new WebSocketServer({ server, path: "/relay" });

wss.on("connection", (ws, req) => {
    const parsed  = url.parse(req.url, true);
    // Expected path: /relay/<code>
    // e.g. /relay/123456
    const parts   = parsed.pathname.split("/").filter(Boolean);
    const code    = parts[1] || "";  // parts[0] = "relay", parts[1] = code
    const roleQ   = parsed.query.role || "auto";  // "host", "client", or "auto"

    if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
        console.log(`[relay] Bad code: "${code}" — closing`);
        ws.close(1008, "Invalid code");
        return;
    }

    // Get or create session
    if (!sessions.has(code)) {
        sessions.set(code, { host: null, client: null, createdAt: Date.now() });
    }
    const sess = sessions.get(code);

    // Determine role
    let role = roleQ;
    if (role === "auto") {
        role = sess.host ? "client" : "host";
    }

    console.log(`[relay] ${role.padEnd(6)} connected for code ${code}  (ip: ${req.socket.remoteAddress})`);

    if (role === "host") {
        if (sess.host && sess.host.readyState === WebSocket.OPEN) {
            // Kick old host
            sess.host.close(1001, "New host connected");
        }
        sess.host = ws;

        // Tell the host we're ready
        ws.send(JSON.stringify({ type: "relay_ready", role: "host", code }));

        // If client already waiting, notify it
        if (sess.client && sess.client.readyState === WebSocket.OPEN) {
            sess.client.send(JSON.stringify({ type: "peer_connected", role: "host" }));
            ws.send(JSON.stringify({ type: "peer_connected", role: "client" }));
        }

    } else { // client (phone)
        if (sess.client && sess.client.readyState === WebSocket.OPEN) {
            sess.client.close(1001, "New client connected");
        }
        sess.client = ws;

        ws.send(JSON.stringify({ type: "relay_ready", role: "client", code }));

        if (sess.host && sess.host.readyState === WebSocket.OPEN) {
            sess.host.send(JSON.stringify({ type: "peer_connected", role: "client" }));
            ws.send(JSON.stringify({ type: "peer_connected", role: "host" }));
        } else {
            ws.send(JSON.stringify({ type: "waiting_for_host" }));
        }
    }

    // ── Pipe messages to the other peer ──────────────────────────
    ws.on("message", (data, isBinary) => {
        const peer = role === "host" ? sess.client : sess.host;
        if (!peer || peer.readyState !== WebSocket.OPEN) {
            // Buffer or drop — peer not connected yet
            return;
        }
        try {
            peer.send(data, { binary: isBinary });
        } catch (e) {
            console.error(`[relay] Send error for ${code}: ${e.message}`);
        }
    });

    // ── Cleanup on disconnect ─────────────────────────────────────
    ws.on("close", (code_, reason) => {
        console.log(`[relay] ${role} disconnected from session ${code}`);
        const peer = role === "host" ? sess.client : sess.host;

        // Notify peer
        if (peer && peer.readyState === WebSocket.OPEN) {
            try {
                peer.send(JSON.stringify({ type: "peer_disconnected", role }));
            } catch (_) {}
        }

        // Clear role in session
        if (role === "host") sess.host = null;
        else sess.client = null;

        // If both gone, delete session
        if (!sess.host && !sess.client) {
            sessions.delete(code);
            console.log(`[relay] Session ${code} closed`);
        }
    });

    ws.on("error", (err) => {
        console.error(`[relay] WS error (${role}/${code}): ${err.message}`);
    });
});

// ── Start ─────────────────────────────────────────────────────────
server.listen(PORT, () => {
    console.log(`✅ RasFocus Relay Server running on port ${PORT}`);
    console.log(`   Health: http://localhost:${PORT}/health`);
    console.log(`   WS:     ws://localhost:${PORT}/relay/<code>`);
    console.log(`   Deploy with nginx + SSL for wss:// support`);
});

// ── Graceful shutdown ─────────────────────────────────────────────
process.on("SIGINT", () => {
    console.log("\nShutting down relay server...");
    wss.close();
    server.close(() => process.exit(0));
});
