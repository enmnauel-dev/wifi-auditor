const WebSocket = require('ws');
const http = require('http');
const PORT = process.env.PORT || 10000;
const DEPLOY_TIME = Date.now();

function sendJSON(ws, data) {
  try { ws.send(JSON.stringify(data)); } catch (e) {}
}

// Guest system: all guests see each other, can set name, send direct messages
const guests = new Map(); // ws -> { id, name }
let guestIdCounter = 0;

function guestList() {
  const list = [];
  for (const [, info] of guests) {
    list.push({ id: info.id, name: info.name });
  }
  return list;
}

function broadcastGuestList() {
  const list = guestList();
  const msg = { type: 'guest_list', guests: list };
  for (const [ws] of guests) {
    sendJSON(ws, msg);
  }
}

const server = http.createServer((req, res) => {
  if (req.url && req.url.includes('/status')) {
    const guestListArr = [];
    for (const [, info] of guests) { guestListArr.push(info); }
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({ guests: guestListArr, count: guests.size, deployed: DEPLOY_TIME, version: 'e94824a' }));
    return;
  }
  res.writeHead(200, {'Content-Type': 'text/plain'});
  res.end(`WiFi Auditor Relay v2 (${new Date().toISOString()})`);
});

const wss = new WebSocket.Server({ server });

function heartbeat() { this.isAlive = true; }

wss.on('connection', ws => {
  ws.isAlive = true;
  ws.on('pong', heartbeat);
  const guestId = 'g' + (++guestIdCounter);
  const defaultName = 'Usuario-' + guestId;
  guests.set(ws, { id: guestId, name: defaultName });
  sendJSON(ws, { type: 'init', id: guestId, guests: guestList() });
  broadcastGuestList();
  const guestCount = guests.size;
  console.log(`Guest connected: ${guestId} (total: ${guestCount}) - ${defaultName}`);
  sendJSON(ws, { type: 'paired', count: guestCount });
  for (const [sock, info] of guests) {
    if (sock !== ws && sock.readyState === WebSocket.OPEN) {
      sendJSON(sock, { type: 'user_joined', id: guestId, name: defaultName, count: guestCount });
    }
  }

  ws.on('message', data => {
    try {
      const msg = JSON.parse(data.toString());
        if (msg.type === 'set_name') {
          const info = guests.get(ws);
          if (info && msg.name) {
            info.name = msg.name.substring(0, 20);
            broadcastGuestList();
            for (const [sock] of guests) {
              if (sock !== ws && sock.readyState === WebSocket.OPEN) {
                sendJSON(sock, { type: 'user_renamed', id: info.id, name: info.name });
              }
            }
          }
        } else if (msg.type === 'get_guests') {
          sendJSON(ws, { type: 'guest_list', guests: guestList() });
        } else if (msg.type === 'message') {
          const from = guests.get(ws);
          if (!from) return;
          const senderName = msg.from || from.name;
          if (msg.to) {
            for (const [targetWs, info] of guests) {
              if (info.id === msg.to && targetWs.readyState === WebSocket.OPEN) {
                sendJSON(targetWs, { type: 'message', from: senderName, from_id: from.id, text: msg.text });
                break;
              }
            }
          } else {
            for (const [targetWs, info] of guests) {
              if (targetWs !== ws && targetWs.readyState === WebSocket.OPEN) {
                sendJSON(targetWs, { type: 'message', from: senderName, from_id: from.id, text: msg.text });
              }
            }
          }
        }
    } catch (e) {
      sendJSON(ws, { type: 'error', text: 'invalid JSON' });
    }
  });

  ws.on('close', () => {
    const guestInfo = guests.get(ws);
    const leftInfo = guestInfo ? guestInfo.name : 'unknown';
    guests.delete(ws);
    broadcastGuestList();
    if (guests.size >= 1) {
      for (const [sock, info] of guests) {
        if (sock.readyState === WebSocket.OPEN) {
          if (guests.size === 1) {
            sendJSON(sock, { type: 'peer_disconnected' });
          }
          sendJSON(sock, { type: 'user_left', id: guestId, name: leftInfo, count: guests.size });
        }
      }
    }
    console.log(`Guest disconnected: ${guestId}`);
  });

  ws.on('error', () => {});
});

const interval = setInterval(() => {
  wss.clients.forEach(ws => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on('close', () => clearInterval(interval));

server.listen(PORT, '0.0.0.0', () => {
  console.log(`WebSocket server running on port ${PORT}`);
});
