#!/bin/bash
# Run this on your Oracle Cloud VM after SSH

# Install Node.js
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# Copy server.js and package.json to the VM first (use scp from your PC)
# Then run:
# cd ~/relay-server && npm install

# Open firewall port
sudo iptables -I INPUT -p tcp --dport 56789 -j ACCEPT
sudo iptables-save > /etc/iptables/rules.v4 2>/dev/null || true

# Create systemd service for auto-start
sudo tee /etc/systemd/system/relay-server.service > /dev/null <<'EOF'
[Unit]
Description=WiFi Auditor Relay Server
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/relay-server
ExecStart=/usr/bin/node /home/ubuntu/relay-server/server.js
Restart=always
RestartSec=5
Environment=PORT=56789

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable relay-server
sudo systemctl start relay-server
sudo systemctl status relay-server
