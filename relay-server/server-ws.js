const WebSocket = require('ws');
const http = require('http');
const sqlite3 = require('sqlite3').verbose();
const PORT = process.env.PORT || 10000;

const DB_PATH = process.env.RENDER ? '/tmp/chat.db' : 'chat.db';
const db = new sqlite3.Database(DB_PATH);
db.run(`CREATE TABLE IF NOT EXISTS users (
  username TEXT PRIMARY KEY, password TEXT NOT NULL, created_at INTEGER NOT NULL
)`);
db.run(`CREATE TABLE IF NOT EXISTS friends (
  username TEXT NOT NULL, friend TEXT NOT NULL, status TEXT DEFAULT 'pending',
  PRIMARY KEY (username, friend)
)`);
db.run(`CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT, from_user TEXT NOT NULL,
  to_user TEXT NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL
)`);

const clients = new Map();

function sendJSON(ws, data) {
  try { ws.send(JSON.stringify(data)); } catch (e) {}
}

function getUser(ws) {
  for (const [user, s] of clients) { if (s === ws) return user; }
  return null;
}

function notifyFriends(username, type) {
  db.all("SELECT friend FROM friends WHERE username = ? AND status = 'accepted'", [username], (err, rows) => {
    if (err || !rows) return;
    for (const row of rows) {
      const sock = clients.get(row.friend);
      if (sock) sendJSON(sock, { type, username });
    }
  });
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
  res.writeHead(200, {'Content-Type': 'text/plain'});
  res.end('WiFi Auditor Relay Server running');
});

const wss = new WebSocket.Server({ server });

function heartbeat() { this.isAlive = true; }

wss.on('connection', ws => {
  ws.isAlive = true;
  ws.on('pong', heartbeat);
  let loggedUser = null;
  let isGuest = true;
  const guestId = 'g' + (++guestIdCounter);
  guests.set(ws, { id: guestId, name: 'Invitado' });
  sendJSON(ws, { type: 'init', id: guestId, guests: guestList() });
  broadcastGuestList();
  // Send waiting/paired status
  const guestCount = guests.size;
  if (guestCount >= 2) {
    for (const [sock] of guests) {
      if (sock.readyState === WebSocket.OPEN) {
        sendJSON(sock, { type: 'paired' });
      }
    }
  } else {
    sendJSON(ws, { type: 'waiting' });
  }
  console.log(`Guest connected: ${guestId} (total: ${guestCount})`);

  ws.on('message', data => {
    try {
      const msg = JSON.parse(data.toString());
      if (isGuest && (msg.type === 'register' || msg.type === 'login')) {
        isGuest = false;
        guests.delete(ws);
        broadcastGuestList();
        handleMessage(ws, msg);
        return;
      }
      if (isGuest) {
        if (msg.type === 'set_name') {
          const info = guests.get(ws);
          if (info && msg.name) {
            info.name = msg.name.substring(0, 20);
            broadcastGuestList();
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
        return;
      }
      handleMessage(ws, msg);
    } catch (e) {
      sendJSON(ws, { type: 'error', text: 'invalid JSON' });
    }
  });

  ws.on('close', () => {
    if (loggedUser) {
      clients.delete(loggedUser);
      notifyFriends(loggedUser, 'friend_offline');
      console.log(`${loggedUser} disconnected`);
    } else {
      guests.delete(ws);
      // Notify remaining guest
      if (guests.size === 1) {
        for (const [sock] of guests) {
          if (sock.readyState === WebSocket.OPEN) {
            sendJSON(sock, { type: 'peer_disconnected' });
          }
        }
      }
      broadcastGuestList();
      console.log(`Guest disconnected: ${guestId}`);
    }
  });

  ws.on('error', () => {});
});

function handleMessage(ws, msg) {
  switch (msg.type) {
    case 'register': return handleRegister(ws, msg);
    case 'login': return handleLogin(ws, msg);
    case 'friend_request': return handleFriendRequest(ws, msg);
    case 'friend_response': return handleFriendResponse(ws, msg);
    case 'get_friends': return handleGetFriends(ws);
    case 'get_pending': return handleGetPending(ws);
    case 'message': return handleMessageSend(ws, msg);
    case 'get_messages': return handleGetMessages(ws, msg);
    default: sendJSON(ws, { type: 'error', text: 'unknown command' });
  }
}

function handleRegister(ws, msg) {
  const { username, password } = msg;
  if (!username || !password || username.length < 2)
    return sendJSON(ws, { type: 'error', text: 'Usuario o contrasena invalidos' });
  db.get("SELECT username FROM users WHERE username = ?", [username], (err, row) => {
    if (err) return sendJSON(ws, { type: 'error', text: 'Error interno' });
    if (row) return sendJSON(ws, { type: 'error', text: 'Usuario ya existe' });
    db.run("INSERT INTO users (username, password, created_at) VALUES (?, ?, ?)",
      [username, password, Date.now()], err2 => {
        if (err2) return sendJSON(ws, { type: 'error', text: 'Error al registrar' });
        sendJSON(ws, { type: 'ok', text: 'Registrado exitosamente' });
        console.log(`Registered: ${username}`);
      });
  });
}

function handleLogin(ws, msg) {
  const { username, password } = msg;
  if (!username || !password)
    return sendJSON(ws, { type: 'error', text: 'Usuario y contrasena requeridos' });
  db.get("SELECT password FROM users WHERE username = ?", [username], (err, row) => {
    if (err) return sendJSON(ws, { type: 'error', text: 'Error interno' });
    if (!row || row.password !== password)
      return sendJSON(ws, { type: 'error', text: 'Usuario o contrasena incorrectos' });
    if (clients.has(username))
      return sendJSON(ws, { type: 'error', text: 'Usuario ya conectado' });
    loggedUser = username;
    clients.set(username, ws);
    sendJSON(ws, { type: 'ok', text: 'Conectado' });
    notifyFriends(username, 'friend_online');
    console.log(`Logged in: ${username}`);
  });
}

function handleFriendRequest(ws, msg) {
  const me = getUser(ws);
  if (!me) return sendJSON(ws, { type: 'error', text: 'No autenticado' });
  const target = msg.to;
  if (target === me) return sendJSON(ws, { type: 'error', text: 'No puedes agregarte a ti mismo' });
  db.get("SELECT username FROM users WHERE username = ?", [target], (err, row) => {
    if (err || !row) return sendJSON(ws, { type: 'error', text: 'Usuario no existe' });
    db.get("SELECT status FROM friends WHERE username = ? AND friend = ?", [me, target], (err2, existing) => {
      if (existing) return sendJSON(ws, { type: 'error', text: 'Solicitud ya enviada' });
      db.run("INSERT INTO friends (username, friend, status) VALUES (?, ?, 'pending')", [me, target]);
      db.run("INSERT INTO friends (username, friend, status) VALUES (?, ?, 'pending')", [target, me]);
      const sock = clients.get(target);
      if (sock) sendJSON(sock, { type: 'friend_request', from: me });
      sendJSON(ws, { type: 'ok', text: `Solicitud enviada a ${target}` });
    });
  });
}

function handleFriendResponse(ws, msg) {
  const me = getUser(ws);
  if (!me) return sendJSON(ws, { type: 'error', text: 'No autenticado' });
  const { from, accept } = msg;
  if (accept) {
    db.run("UPDATE friends SET status = 'accepted' WHERE username = ? AND friend = ? AND status = 'pending'", [me, from]);
    db.run("UPDATE friends SET status = 'accepted' WHERE username = ? AND friend = ? AND status = 'pending'", [from, me]);
    sendJSON(ws, { type: 'ok', text: `${from} agregado como amigo` });
    const sock = clients.get(from);
    if (sock) sendJSON(sock, { type: 'friend_request_accepted', by: me });
  } else {
    db.run("DELETE FROM friends WHERE username = ? AND friend = ?", [me, from]);
    db.run("DELETE FROM friends WHERE username = ? AND friend = ?", [from, me]);
    sendJSON(ws, { type: 'ok', text: 'Solicitud rechazada' });
  }
}

function handleGetFriends(ws) {
  const me = getUser(ws);
  if (!me) return sendJSON(ws, { type: 'error', text: 'No autenticado' });
  db.all("SELECT friend FROM friends WHERE username = ? AND status = 'accepted'", [me], (err, rows) => {
    if (err) return sendJSON(ws, { type: 'error', text: 'Error' });
    sendJSON(ws, { type: 'friends', list: rows.map(r => ({ username: r.friend, online: clients.has(r.friend) })) });
  });
}

function handleGetPending(ws) {
  const me = getUser(ws);
  if (!me) return sendJSON(ws, { type: 'error', text: 'No autenticado' });
  db.all("SELECT username FROM friends WHERE friend = ? AND status = 'pending'", [me], (err, rows) => {
    if (err) return sendJSON(ws, { type: 'error', text: 'Error' });
    sendJSON(ws, { type: 'pending', list: rows.map(r => r.username) });
  });
}

function handleMessageSend(ws, msg) {
  const me = getUser(ws);
  if (!me) return sendJSON(ws, { type: 'error', text: 'No autenticado' });
  const { to, text } = msg;
  if (!to || !text) return sendJSON(ws, { type: 'error', text: 'Destino y texto requeridos' });
  const now = Date.now();
  db.run("INSERT INTO messages (from_user, to_user, text, timestamp) VALUES (?, ?, ?, ?)", [me, to, text, now]);
  const sock = clients.get(to);
  if (sock) sendJSON(sock, { type: 'message', from: me, text, timestamp: now });
  sendJSON(ws, { type: 'ok', text: 'Mensaje enviado' });
}

function handleGetMessages(ws, msg) {
  const me = getUser(ws);
  if (!me) return sendJSON(ws, { type: 'error', text: 'No autenticado' });
  const other = msg.with;
  db.all(
    "SELECT from_user, to_user, text, timestamp FROM messages WHERE (from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?) ORDER BY timestamp",
    [me, other, other, me], (err, rows) => {
      if (err) return sendJSON(ws, { type: 'error', text: 'Error' });
      sendJSON(ws, { type: 'messages', list: rows });
    });
}

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
