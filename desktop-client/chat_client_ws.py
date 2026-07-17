#!/usr/bin/env python3
import json, threading, os, struct, socket as sock
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from datetime import datetime
import websocket
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_config.json")
AES_KEY = b"WiFiAud1t0r!2026"
BT_UUID = "00001101-0000-1000-8000-00805F9B34FB"

def load_config():
    try:
        with open(CONFIG_FILE) as f: return json.load(f)
    except: return {"server": "wifi-auditor.onrender.com:443"}

def save_config(**kw):
    cfg = load_config()
    cfg.update(kw)
    with open(CONFIG_FILE, "w") as f: json.dump(cfg, f)

def encrypt_msg(text):
    aesgcm = AESGCM(AES_KEY)
    nonce = os.urandom(12)
    ct = aesgcm.encrypt(nonce, text.encode(), None)
    return nonce + ct

def decrypt_msg(data):
    try:
        aesgcm = AESGCM(AES_KEY)
        nonce = data[:12]
        ct = data[12:]
        return aesgcm.decrypt(nonce, ct, None).decode()
    except: return ""

def scan_bt_devices():
    try:
        import subprocess, re
        ps = '''
$devices = New-Object System.Collections.ArrayList
$bt = Get-PnpDevice -Class Bluetooth -Status OK
foreach ($d in $bt) {
    $id = $d.DeviceID
    if ($id -match 'DEV_([0-9A-Fa-f]{12})') {
        $mac = $matches[1] -replace '(..)(?!$)', '$1:'
        [void]$devices.Add(@{Name=$d.FriendlyName; Address=$mac})
    }
}
$devices | ConvertTo-Json
'''
        result = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=10)
        if result.returncode == 0 and result.stdout.strip():
            data = json.loads(result.stdout)
            if isinstance(data, list):
                seen = set()
                unique = []
                for d in data:
                    if d.get("Address") not in seen:
                        seen.add(d.get("Address"))
                        unique.append(d)
                return unique
            return [data]
    except: pass
    return []

def get_rfcomm_channel(addr, uuid_str):
    """Query SDP for the RFCOMM channel of a UUID on a remote device"""
    try:
        mac = addr.replace(':', '').upper()
        uuid_short = uuid_str.replace('-', '').upper()
        # Convert MAC string to UInt64 for the Bluetooth API
        ps = f'''
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Devices.Bluetooth.BluetoothDevice]::FromBluetoothAddressAsync([ulong]::Parse("{mac}", [System.Globalization.NumberStyles]::HexNumber))
'''
        result = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=10)
        return None
    except:
        return None

class DesktopClient:
    def __init__(self, root):
        self.root = root
        self.root.title("WiFi Auditor - Chat")
        self.root.geometry("500x500")
        self.root.configure(bg="#1a1a2e")
        self.ws = None
        self.bt_sock = None
        self.bt_server_sock = None
        self.running = False
        self.username = None
        self.password = None
        self.cfg = load_config()
        self.my_nick = "Invitado"
        self.use_bt = False
        self.bt_target = ""
        self.build_login_screen()

    def clear(self):
        for w in self.root.winfo_children():
            w.destroy()

    def build_login_screen(self):
        self.clear()
        self.root.geometry("400x400")
        frame = tk.Frame(self.root, bg="#1a1a2e")
        frame.pack(expand=True)
        tk.Label(frame, text="WiFi Auditor Chat", font=("Arial", 18, "bold"), fg="#64B5F6", bg="#1a1a2e").pack(pady=(0, 10))
        self.mode_lbl = tk.Label(frame, text="Modo: WebSocket", font=("Arial", 10), fg="#FFB74D", bg="#1a1a2e")
        self.mode_lbl.pack()
        tk.Button(frame, text="Cambiar a Bluetooth", bg="#1565C0", fg="white", relief="flat", padx=20, pady=5, command=self.toggle_mode).pack(pady=5)

        self.bt_frame = tk.Frame(frame, bg="#1a1a2e")
        tk.Label(self.bt_frame, text="Dispositivo Bluetooth:", fg="white", bg="#1a1a2e", anchor="w").pack(fill="x", padx=40)
        self.bt_entry = tk.Entry(self.bt_frame, bg="#0f3460", fg="white", insertbackground="white", relief="flat")
        self.bt_entry.pack(fill="x", padx=40, pady=(0, 5), ipady=4)
        self.bt_entry.insert(0, self.cfg.get("bt_address", ""))
        btn_frame2 = tk.Frame(self.bt_frame, bg="#1a1a2e")
        btn_frame2.pack(pady=5)
        tk.Button(btn_frame2, text="Escanear BT", bg="#1565C0", fg="white", relief="flat", padx=10, pady=3, command=self.scan_bt).pack(side="left", padx=3)
        tk.Button(btn_frame2, text="Servidor BT", bg="#FF9800", fg="white", relief="flat", padx=10, pady=3, command=self.start_bt_server).pack(side="left", padx=3)
        tk.Button(btn_frame2, text="Conectar BT", bg="#4CAF50", fg="white", relief="flat", padx=10, pady=3, command=self.connect_bt).pack(side="left", padx=3)
        self.bt_srv_status = tk.Label(self.bt_frame, text="", fg="#90CAF9", bg="#1a1a2e", font=("Arial", 8))
        self.bt_srv_status.pack()
        self.bt_devices_frame = tk.Frame(self.bt_frame, bg="#1a1a2e")
        self.bt_devices_frame.pack(fill="x", padx=40, pady=5)
        self.bt_devices = []

        tk.Label(self.ws_frame, text="Tu apodo:", fg="white", bg="#1a1a2e", anchor="w").pack(fill="x", padx=40)
        self.nick_entry = tk.Entry(self.ws_frame, bg="#0f3460", fg="white", insertbackground="white", relief="flat")
        self.nick_entry.pack(fill="x", padx=40, pady=(0, 5), ipady=4)
        self.nick_entry.insert(0, self.cfg.get("nickname", "Invitado"))
        tk.Label(self.ws_frame, text="Servidor:", fg="white", bg="#1a1a2e", anchor="w").pack(fill="x", padx=40)
        self.server_entry = tk.Entry(self.ws_frame, bg="#0f3460", fg="white", insertbackground="white", relief="flat")
        self.server_entry.pack(fill="x", padx=40, pady=(0, 10), ipady=4)
        self.server_entry.insert(0, self.cfg.get("server", "wifi-auditor.onrender.com:443"))

        self.status_lbl = tk.Label(frame, text="", fg="#FFB74D", bg="#1a1a2e")
        self.status_lbl.pack()
        btn_frame = tk.Frame(frame, bg="#1a1a2e")
        btn_frame.pack(pady=10)
        tk.Button(btn_frame, text="Conectar invitado", bg="#FF9800", fg="white", relief="flat", padx=20, pady=5, command=self.connect_guest).pack(side="left", padx=5)
        tk.Button(btn_frame, text="Registrarse", bg="#1565C0", fg="white", relief="flat", padx=15, pady=5, command=self.show_register).pack(side="left", padx=3)
        tk.Button(btn_frame, text="Iniciar sesion", bg="#4CAF50", fg="white", relief="flat", padx=15, pady=5, command=self.show_login).pack(side="left", padx=3)

        self.show_mode()

    def show_mode(self):
        if self.use_bt:
            self.mode_lbl.config(text="Modo: Bluetooth")
            self.ws_frame.pack_forget()
            self.bt_frame.pack(fill="x", pady=5)
        else:
            self.mode_lbl.config(text="Modo: WebSocket")
            self.bt_frame.pack_forget()
            self.ws_frame.pack(fill="x")

    def toggle_mode(self):
        self.use_bt = not self.use_bt
        self.show_mode()

    def scan_bt(self):
        self.status_lbl.config(text="Escaneando dispositivos Bluetooth...")
        def run():
            devices = scan_bt_devices()
            self.root.after(0, lambda: self.show_bt_devices(devices))
        threading.Thread(target=run, daemon=True).start()

    def show_bt_devices(self, devices):
        for w in self.bt_devices_frame.winfo_children(): w.destroy()
        self.bt_devices = devices
        if devices:
            for d in devices:
                name = d.get("Name", "?")
                addr = d.get("Address", "?")
                btn = tk.Button(self.bt_devices_frame, text=f"{name} ({addr})", bg="#0f3460", fg="#90CAF9",
                                relief="flat", anchor="w", padx=8, pady=2, cursor="hand2",
                                command=lambda a=addr: self.select_bt_device(a))
                btn.pack(fill="x", pady=1)
            self.status_lbl.config(text=f"{len(devices)} dispositivo(s) encontrado(s) - haz clic en uno")
        else:
            self.status_lbl.config(text="No se encontraron dispositivos BT. Ingresa la MAC manualmente.")

    def select_bt_device(self, addr):
        self.bt_entry.delete(0, tk.END)
        self.bt_entry.insert(0, addr)
        for w in self.bt_devices_frame.winfo_children():
            name = w.cget("text") if hasattr(w, 'cget') else ""
            if addr in name:
                w.config(bg="#1E3A5F", fg="white")
            else:
                w.config(bg="#0f3460", fg="#90CAF9")
        self.status_lbl.config(text=f"Seleccionado: {addr}")

    PC_BT_ADDR = "D8:0F:99:3A:1F:52"
    BT_SRV_CHANNEL = 10

    def start_bt_server(self):
        def run():
            try:
                s = sock.socket(sock.AF_BLUETOOTH, sock.SOCK_STREAM, sock.BTPROTO_RFCOMM)
                s.bind((self.PC_BT_ADDR, self.BT_SRV_CHANNEL))
                s.listen(1)
                self.bt_server_sock = s
                self.root.after(0, lambda: self.bt_srv_status.config(
                    text=f"Servidor BT activo en canal {self.BT_SRV_CHANNEL}", fg="#4CAF50"))
                self.bt_server_loop(s)
            except Exception as e:
                self.root.after(0, lambda: self.bt_srv_status.config(
                    text=f"Error servidor BT: {e}", fg="#d32f2f"))
        threading.Thread(target=run, daemon=True).start()

    def bt_server_loop(self, server_sock):
        try:
            conn, addr = server_sock.accept()
            self.bt_sock = conn
            self.bt_target = addr[0]
            self.running = True
            self.status_lbl.config(text=f"Conectado desde {addr[0]}")
            self.root.after(0, self.build_main_screen)
            threading.Thread(target=self.bt_read_loop, daemon=True).start()
        except:
            pass

    def connect_bt(self):
        addr = self.bt_entry.get().strip()
        if not addr:
            messagebox.showwarning("Bluetooth", "Ingresa la direccion MAC del dispositivo")
            return
        self.bt_target = addr
        save_config(bt_address=addr)
        self.status_lbl.config(text="Conectando por Bluetooth...")
        def run():
            try:
                s = None
                conn_ch = None
                for ch in range(1, 31):
                    try:
                        s = sock.socket(sock.AF_BLUETOOTH, sock.SOCK_STREAM, sock.BTPROTO_RFCOMM)
                        s.settimeout(3)
                        s.connect((addr, ch))
                        self.bt_sock = s
                        conn_ch = ch
                        break
                    except:
                        if s: s.close()
                        s = None
                if self.bt_sock:
                    self.bt_sock.settimeout(None)
                    self.running = True
                    self.root.after(0, lambda: messagebox.showinfo("Bluetooth", f"Conectado en canal {conn_ch}"))
                    self.root.after(100, self.build_main_screen)
                    threading.Thread(target=self.bt_read_loop, daemon=True).start()
                else:
                    self.root.after(0, lambda: self.status_lbl.config(text="Error BT: no se pudo conectar en ningun canal"))
            except Exception as e:
                self.root.after(0, lambda: self.status_lbl.config(text=f"Error BT: {e}"))
        threading.Thread(target=run, daemon=True).start()

    def bt_read_loop(self):
        while self.running and self.bt_sock:
            try:
                data = self.bt_sock.recv(4096)
                if not data: break
                text = decrypt_msg(data)
                if text:
                    self.root.after(0, lambda t=text: self.display_message("Otro", t, 0))
            except sock.timeout:
                continue
            except: break
        self.root.after(0, self.on_disconnect)

    def connect_guest(self):
        if self.use_bt:
            self.connect_bt()
            return
        host = self.server_entry.get().strip()
        nick = self.nick_entry.get().strip() or os.environ.get('COMPUTERNAME', 'PC')
        self.my_nick = nick
        save_config(server=host, nickname=nick)
        protocol = "wss" if ":443" in host else "ws"
        url = f"{protocol}://{host}"
        self.status_lbl.config(text="Conectando...")
        def run():
            try:
                ws = websocket.create_connection(url, timeout=60)
                self.ws = ws
                self.running = True
                self.root.after(0, self.build_main_screen)
                threading.Thread(target=self.read_loop, daemon=True).start()
            except Exception as e:
                self.root.after(0, lambda: self.status_lbl.config(text=f"Error: {e}"))
        threading.Thread(target=run, daemon=True).start()

    def connect_and_send(self, msg, login_after=False):
        host = self.server_entry.get().strip()
        save_config(server=host)
        protocol = "wss" if ":443" in host else "ws"
        url = f"{protocol}://{host}"
        self.status_lbl.config(text="Conectando...")
        def run():
            try:
                ws = websocket.create_connection(url, timeout=30)
                self.ws = ws
                if login_after:
                    ws.send(json.dumps(msg))
                    import time; time.sleep(0.5)
                    ws.send(json.dumps({"type": "login", "username": self.username, "password": self.password}))
                else:
                    ws.send(json.dumps(msg))
                self.wait_for_login(ws)
            except Exception as e:
                self.root.after(0, lambda: self.status_lbl.config(text=f"Error: {e}"))
        threading.Thread(target=run, daemon=True).start()

    def show_register(self):
        u = simpledialog.askstring("Registro", "Nombre de usuario:")
        if not u: return
        p = simpledialog.askstring("Registro", "Contrasena:", show="*")
        if not p: return
        self.username = u; self.password = p
        self.connect_and_send({"type": "register", "username": u, "password": p}, login_after=True)

    def show_login(self):
        u = simpledialog.askstring("Login", "Usuario:")
        if not u: return
        p = simpledialog.askstring("Login", "Contrasena:", show="*")
        if not p: return
        self.username = u; self.password = p
        self.connect_and_send({"type": "login", "username": u, "password": p})

    def wait_for_login(self, ws):
        try:
            data = json.loads(ws.recv())
            if data.get("type") == "ok" and data.get("text") == "Conectado":
                self.running = True
                self.root.after(0, self.build_main_screen)
                threading.Thread(target=self.read_loop, daemon=True).start()
            elif data.get("type") == "ok":
                self.root.after(0, lambda: self.status_lbl.config(text=data.get("text", "")))
            else:
                self.root.after(0, lambda: messagebox.showerror("Error", data.get("text", "")))
                self.root.after(0, self.build_login_screen)
        except:
            self.root.after(0, lambda: self.status_lbl.config(text="Error de conexion"))

    def send_raw(self, data):
        if self.ws:
            try: self.ws.send(json.dumps(data))
            except: pass

    def read_loop(self):
        while self.running and self.ws:
            try:
                raw = self.ws.recv()
                if raw is None: break
                data = json.loads(raw)
                self.root.after(0, lambda m=data: self.handle_message(m))
            except:
                break
        self.root.after(0, self.on_disconnect)

    def on_disconnect(self):
        self.running = False
        if self.ws:
            try: self.ws.close()
            except: pass
            self.ws = None
        if self.bt_sock:
            try: self.bt_sock.close()
            except: pass
            self.bt_sock = None
        self.build_login_screen()

    def handle_message(self, msg):
        t = msg.get("type")
        if t == "waiting":
            self.status_lbl.config(text="Esperando otro dispositivo...")
        elif t == "paired":
            self.status_lbl.config(text="Conectado!")
            count = msg.get("count", 2)
            self.display_system(f"Conectado! ({count} dispositivos en sala)")
        elif t == "peer_disconnected":
            self.status_lbl.config(text="El otro dispositivo se desconecto")
            self.display_system("El otro dispositivo se desconecto")
        elif t == "user_joined":
            name = msg.get("name", "Alguien")
            count = msg.get("count", 0)
            self.status_lbl.config(text=f"{count} dispositivos conectados")
            self.display_system(f"{name} se unio ({count} en sala)")
        elif t == "user_left":
            name = msg.get("name", "Alguien")
            count = msg.get("count", 0)
            if count <= 1:
                self.status_lbl.config(text="Esperando otro dispositivo...")
            else:
                self.status_lbl.config(text=f"{count} dispositivos conectados")
            self.display_system(f"{name} se fue ({count} restantes)")
        elif t == "user_renamed":
            name = msg.get("name", "Alguien")
            self.display_system(f"Alguien ahora es {name}")
        elif t == "ok":
            self.status_lbl.config(text=msg.get("text", ""))
        elif t == "error":
            self.status_lbl.config(text=msg.get("text", ""))
        elif t == "message":
            sender = msg.get("from", "Invitado")
            self.display_message(sender, msg["text"], msg.get("timestamp", 0))
        elif t == "friends":
            if hasattr(self, 'friends_frame'):
                for w in self.friends_frame.winfo_children(): w.destroy()
                for f in msg.get("list", []):
                    color = "#4CAF50" if f.get("online") else "#757575"
                    tk.Label(self.friends_frame, text=f"  {f['username']}", fg=color, bg="#16213e",
                             font=("Arial", 9)).pack(fill="x", padx=5, pady=1)
        elif t == "pending":
            if hasattr(self, 'pending_frame'):
                for w in self.pending_frame.winfo_children(): w.destroy()
                for u in msg.get("list", []):
                    tk.Label(self.pending_frame, text=u, fg="#FFB74D", bg="#16213e",
                             font=("Arial", 9)).pack(fill="x", padx=5, pady=1)
        elif t == "friend_request":
            a = messagebox.askyesno("Solicitud", f"{msg['from']} quiere ser tu amigo. Aceptar?")
            self.send_raw({"type": "friend_response", "from": msg["from"], "accept": a})
        elif t == "friend_request_accepted":
            messagebox.showinfo("Amigos", f"{msg['by']} acepto tu solicitud!")

    def display_system(self, text):
        self.chat_display.config(state="normal")
        self.chat_display.insert(tk.END, f"  {text}\n", "system")
        self.chat_display.see(tk.END)
        self.chat_display.config(state="disabled")

    def build_main_screen(self):
        self.clear(); self.root.geometry("500x500")
        top = tk.Frame(self.root, bg="#075E54")
        top.pack(fill="x")
        tk.Label(top, text="WiFi Auditor Chat", font=("Arial", 14, "bold"), fg="white", bg="#075E54").pack(side="left", padx=14, pady=10)
        self.status_lbl = tk.Label(top, text="Conectado", fg="#81C784", bg="#075E54", font=("Arial", 9))
        self.status_lbl.pack(side="left", padx=8)
        tk.Button(top, text="Desconectar", bg="#d32f2f", fg="white", relief="flat", font=("Arial", 9),
                  command=self.disconnect).pack(side="right", padx=10, pady=8)
        bg_color = "#0B141A"
        self.chat_frame = tk.Frame(self.root, bg=bg_color)
        self.chat_frame.pack(fill="both", expand=True)
        scroll = tk.Scrollbar(self.chat_frame, bg="#1f2c33", troughcolor="#0B141A")
        scroll.pack(side="right", fill="y")
        self.chat_display = tk.Text(self.chat_frame, bg="#0B141A", fg="white", font=("Arial", 11), wrap="word",
                                      state="disabled", relief="flat", borderwidth=0, padx=10, pady=5,
                                      yscrollcommand=scroll.set)
        self.chat_display.pack(fill="both", expand=True)
        scroll.config(command=self.chat_display.yview)
        self.chat_display.tag_config("me", justify="right", foreground="#90CAF9",
                                       font=("Arial", 11), spacing1=4, spacing2=4, spacing3=4, lmargin1=80)
        self.chat_display.tag_config("other", justify="left", foreground="#A5D6A7",
                                       font=("Arial", 11), spacing1=4, spacing2=4, spacing3=4, lmargin1=10)
        self.chat_display.tag_config("system", justify="center", foreground="#8696A0", font=("Arial", 9))
        inp = tk.Frame(self.root, bg="#1f2c33")
        inp.pack(fill="x")
        self.msg_entry = tk.Entry(inp, bg="#2a3942", fg="white", insertbackground="white", relief="flat",
                                    font=("Arial", 12), borderwidth=0)
        self.msg_entry.pack(side="left", fill="x", expand=True, padx=(8, 4), pady=8, ipady=6)
        self.msg_entry.bind("<Return>", lambda e: self.send_message())
        tk.Button(inp, text="Enviar", bg="#00a884", fg="white", relief="flat", font=("Arial", 10, "bold"),
                  padx=18, command=self.send_message).pack(side="right", padx=(0, 8), pady=8, ipady=4)

    def display_message(self, from_user, text, timestamp):
        self.chat_display.config(state="normal")
        tag = "me" if from_user == "Yo" else "other"
        label = "" if from_user == "Yo" else f"{from_user}: "
        self.chat_display.insert(tk.END, f"{label}{text}\n", tag)
        self.chat_display.see(tk.END)
        self.chat_display.config(state="disabled")

    def send_message(self):
        text = self.msg_entry.get().strip()
        if not text: return
        self.msg_entry.delete(0, tk.END)
        self.display_message("Yo", text, int(datetime.now().timestamp() * 1000))
        if self.bt_sock:
            try:
                self.bt_sock.send(encrypt_msg(text))
            except:
                self.status_lbl.config(text="Error al enviar por BT")
        elif self.ws:
            self.send_raw({"type": "message", "from": self.my_nick, "text": text})

    def disconnect(self):
        self.running = False
        if self.ws:
            try: self.ws.close()
            except: pass
            self.ws = None
        if self.bt_sock:
            try: self.bt_sock.close()
            except: pass
            self.bt_sock = None
        self.build_login_screen()

if __name__ == "__main__":
    root = tk.Tk()
    app = DesktopClient(root)
    root.mainloop()
