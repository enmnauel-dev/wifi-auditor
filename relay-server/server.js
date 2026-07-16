const net = require('net');
const PORT = process.env.PORT || 56789;

const rooms = new Map(); // room -> [socket1, socket2]

function joinRoom(socket) {
  for (const [room, members] of rooms) {
    if (members.length < 2 && !members.includes(socket)) {
      members.push(socket);
      console.log(`[${room}] Joined (${members.length}/2)`);
      if (members.length === 2) {
        members.forEach(s => s.write(JSON.stringify({type: "paired"}) + "\n"));
        console.log(`[${room}] Paired!`);
      }
      return room;
    }
  }
  const roomId = `room_${rooms.size}`;
  rooms.set(roomId, [socket]);
  console.log(`[${roomId}] Created (1/2)`);
  socket.write(JSON.stringify({type: "waiting"}) + "\n");
  return roomId;
}

function leaveRoom(socket) {
  for (const [room, members] of rooms) {
    const idx = members.indexOf(socket);
    if (idx !== -1) {
      members.splice(idx, 1);
      console.log(`[${room}] Left (${members.length}/2)`);
      if (members.length === 0) {
        rooms.delete(room);
        console.log(`[${room}] Removed`);
      } else {
        members[0].write(JSON.stringify({type: "peer_disconnected"}) + "\n");
      }
      return;
    }
  }
}

const server = net.createServer(socket => {
  console.log(`Client connected from ${socket.remoteAddress}`);
  socket.setEncoding('utf8');
  const room = joinRoom(socket);

  let buffer = '';
  socket.on('data', chunk => {
    buffer += chunk;
    const lines = buffer.split('\n');
    buffer = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      try {
        const msg = JSON.parse(line);
        if (msg.type === "message" && msg.text) {
          const members = rooms.get(room);
          if (members) {
            const peer = members.find(s => s !== socket);
            if (peer) {
              peer.write(JSON.stringify({type: "message", text: msg.text}) + "\n");
            }
          }
        }
      } catch (e) {
        socket.write(JSON.stringify({type: "error", text: "invalid JSON"}) + "\n");
      }
    }
  });

  socket.on('close', () => {
    leaveRoom(socket);
    console.log(`Client disconnected from ${socket.remoteAddress}`);
  });

  socket.on('error', () => {
    leaveRoom(socket);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Relay server listening on port ${PORT}`);
});
