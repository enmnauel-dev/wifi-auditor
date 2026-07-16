const net = require('net');
const sqlite3 = require('sqlite3').verbose();
const PORT = process.env.PORT || 56789;

const DB_PATH = process.env.RENDER ? '/tmp/chat.db' : 'chat.db';
const db = new sqlite3.Database(DB_PATH);
db.run(`CREATE TABLE IF NOT EXISTS users (
  username TEXT PRIMARY KEY,
  password TEXT NOT NULL,
  created_at INTEGER NOT NULL
)`);
db.run(`CREATE TABLE IF NOT EXISTS friends (
  username TEXT NOT NULL,
  friend TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  PRIMARY KEY (username, friend)
)`);
db.run(`CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  from_user TEXT NOT NULL,
  to_user TEXT NOT NULL,
  text TEXT NOT NULL,
  timestamp INTEGER NOT NULL
)`);

const clients = new Map();

function sendJSON(socket, data) {
  try { socket.write(JSON.stringify(data) + '\n'); } catch (e) {}
}

function getUser(socket) {
  for (const [user, s] of clients) { if (s === socket) return user; }
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

const server = net.createServer(socket => {
  socket.setEncoding('utf8');
  let loggedUser = null;
  let buffer = '';

  socket.on('data', chunk => {
    buffer += chunk;
    const lines = buffer.split('\n');
    buffer = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      try {
        const msg = JSON.parse(line);
        handleMessage(socket, msg);
      } catch (e) {
        sendJSON(socket, { type: 'error', text: 'invalid JSON' });
      }
    }
  });

  socket.on('close', () => {
    if (loggedUser) {
      clients.delete(loggedUser);
      notifyFriends(loggedUser, 'friend_offline');
      console.log(`${loggedUser} disconnected`);
    }
  });

  socket.on('error', () => {});
});

function handleMessage(socket, msg) {
  switch (msg.type) {
    case 'register':
      return handleRegister(socket, msg);
    case 'login':
      return handleLogin(socket, msg);
    case 'friend_request':
      return handleFriendRequest(socket, msg);
    case 'friend_response':
      return handleFriendResponse(socket, msg);
    case 'get_friends':
      return handleGetFriends(socket);
    case 'get_pending':
      return handleGetPending(socket);
    case 'message':
      return handleMessageSend(socket, msg);
    case 'get_messages':
      return handleGetMessages(socket, msg);
    default:
      sendJSON(socket, { type: 'error', text: 'unknown command' });
  }
}

function handleRegister(socket, msg) {
  const { username, password } = msg;
  if (!username || !password || username.length < 2) {
    return sendJSON(socket, { type: 'error', text: 'Usuario o contrasena invalidos' });
  }
  db.get("SELECT username FROM users WHERE username = ?", [username], (err, row) => {
    if (err) return sendJSON(socket, { type: 'error', text: 'Error interno' });
    if (row) return sendJSON(socket, { type: 'error', text: 'Usuario ya existe' });
    db.run("INSERT INTO users (username, password, created_at) VALUES (?, ?, ?)",
      [username, password, Date.now()], err2 => {
        if (err2) return sendJSON(socket, { type: 'error', text: 'Error al registrar' });
        sendJSON(socket, { type: 'ok', text: 'Registrado exitosamente' });
        console.log(`User registered: ${username}`);
      });
  });
}

function handleLogin(socket, msg) {
  const { username, password } = msg;
  if (!username || !password) {
    return sendJSON(socket, { type: 'error', text: 'Usuario y contrasena requeridos' });
  }
  db.get("SELECT password FROM users WHERE username = ?", [username], (err, row) => {
    if (err) return sendJSON(socket, { type: 'error', text: 'Error interno' });
    if (!row || row.password !== password) {
      return sendJSON(socket, { type: 'error', text: 'Usuario o contrasena incorrectos' });
    }
    if (clients.has(username)) {
      return sendJSON(socket, { type: 'error', text: 'Usuario ya conectado' });
    }
    loggedUser = username;
    clients.set(username, socket);
    sendJSON(socket, { type: 'ok', text: 'Conectado' });
    notifyFriends(username, 'friend_online');
    console.log(`User logged in: ${username}`);
  });
}

function handleFriendRequest(socket, msg) {
  const me = getUser(socket);
  if (!me) return sendJSON(socket, { type: 'error', text: 'No autenticado' });
  const target = msg.to;
  if (target === me) return sendJSON(socket, { type: 'error', text: 'No puedes agregarte a ti mismo' });
  db.get("SELECT username FROM users WHERE username = ?", [target], (err, row) => {
    if (err || !row) return sendJSON(socket, { type: 'error', text: 'Usuario no existe' });
    db.get("SELECT status FROM friends WHERE username = ? AND friend = ?", [me, target], (err2, existing) => {
      if (existing) return sendJSON(socket, { type: 'error', text: 'Solicitud ya enviada' });
      db.run("INSERT INTO friends (username, friend, status) VALUES (?, ?, 'pending')", [me, target]);
      db.run("INSERT INTO friends (username, friend, status) VALUES (?, ?, 'pending')", [target, me]);
      const sock = clients.get(target);
      if (sock) sendJSON(sock, { type: 'friend_request', from: me });
      sendJSON(socket, { type: 'ok', text: `Solicitud enviada a ${target}` });
    });
  });
}

function handleFriendResponse(socket, msg) {
  const me = getUser(socket);
  if (!me) return sendJSON(socket, { type: 'error', text: 'No autenticado' });
  const { from, accept } = msg;
  if (accept) {
    db.run("UPDATE friends SET status = 'accepted' WHERE username = ? AND friend = ? AND status = 'pending'", [me, from]);
    db.run("UPDATE friends SET status = 'accepted' WHERE username = ? AND friend = ? AND status = 'pending'", [from, me]);
    sendJSON(socket, { type: 'ok', text: `${from} agregado como amigo` });
    const sock = clients.get(from);
    if (sock) sendJSON(sock, { type: 'friend_request_accepted', by: me });
  } else {
    db.run("DELETE FROM friends WHERE username = ? AND friend = ?", [me, from]);
    db.run("DELETE FROM friends WHERE username = ? AND friend = ?", [from, me]);
    sendJSON(socket, { type: 'ok', text: 'Solicitud rechazada' });
  }
}

function handleGetFriends(socket) {
  const me = getUser(socket);
  if (!me) return sendJSON(socket, { type: 'error', text: 'No autenticado' });
  db.all("SELECT friend FROM friends WHERE username = ? AND status = 'accepted'", [me], (err, rows) => {
    if (err) return sendJSON(socket, { type: 'error', text: 'Error' });
    const friends = rows.map(r => ({
      username: r.friend,
      online: clients.has(r.friend)
    }));
    sendJSON(socket, { type: 'friends', list: friends });
  });
}

function handleGetPending(socket) {
  const me = getUser(socket);
  if (!me) return sendJSON(socket, { type: 'error', text: 'No autenticado' });
  db.all("SELECT username FROM friends WHERE friend = ? AND status = 'pending'", [me], (err, rows) => {
    if (err) return sendJSON(socket, { type: 'error', text: 'Error' });
    sendJSON(socket, { type: 'pending', list: rows.map(r => r.username) });
  });
}

function handleMessageSend(socket, msg) {
  const me = getUser(socket);
  if (!me) return sendJSON(socket, { type: 'error', text: 'No autenticado' });
  const { to, text } = msg;
  if (!to || !text) return sendJSON(socket, { type: 'error', text: 'Destino y texto requeridos' });
  const now = Date.now();
  db.run("INSERT INTO messages (from_user, to_user, text, timestamp) VALUES (?, ?, ?, ?)", [me, to, text, now]);
  const sock = clients.get(to);
  if (sock) sendJSON(sock, { type: 'message', from: me, text, timestamp: now });
  sendJSON(socket, { type: 'ok', text: 'Mensaje enviado' });
}

function handleGetMessages(socket, msg) {
  const me = getUser(socket);
  if (!me) return sendJSON(socket, { type: 'error', text: 'No autenticado' });
  const other = msg.with;
  db.all(
    "SELECT from_user, to_user, text, timestamp FROM messages WHERE (from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?) ORDER BY timestamp",
    [me, other, other, me], (err, rows) => {
      if (err) return sendJSON(socket, { type: 'error', text: 'Error' });
      sendJSON(socket, { type: 'messages', list: rows });
    });
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Chat server v2 running on port ${PORT}`);
  console.log('Features: registration, friends, messaging');
});
