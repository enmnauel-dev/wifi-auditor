# WiFi Auditor - Sistema de Chat

## Servidor Relay (Node.js)

### Local (pruebas)
```bash
cd relay-server
npm install
node server.js        # Relay simple
# o
node server-v2.js     # Con usuarios, amigos, historial
```

### Oracle Cloud (gratis 24/7)
```bash
# 1. Subir archivos
scp -r relay-server ubuntu@<IP>:~/

# 2. SSH y ejecutar
ssh ubuntu@<IP>
cd ~/relay-server
chmod +x oracle-setup.sh
./oracle-setup.sh

# 3. Abrir puerto 56789 en firewall de Oracle
```

### Fly.io (gratis, duerme nunca)
```bash
cd relay-server
fly launch
fly deploy
# URL: nombre.fly.dev:56789
```

## Cliente Desktop (Python)
```bash
cd desktop-client
python chat_client.py
# Requiere: Python 3, Tkinter (viene incluido)
```

## App Android
- Abrir la app en Android Studio
- Build & Run
- Modo Local: Chat directo por IP
- Modo Remoto: Conectar al relay

## Puerto
Por defecto: **56789**
