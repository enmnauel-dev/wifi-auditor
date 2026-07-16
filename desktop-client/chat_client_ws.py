#!/usr/bin/env python3
import json, threading, os
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from datetime import datetime
import websocket

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat_config.json")

def load_config():
    try:
        with open(CONFIG_FILE) as f: return json.load(f)
    except: return {"server": "wifi-auditor.onrender.com:443"}

def save_config(**kw):
    cfg = load_config()
    cfg.update(kw)
    with open(CONFIG_FILE, "w") as f: json.dump(cfg, f)

class DesktopClient:
    def __init__(self, root):
        self.root = root
        self.root.title("WiFi Auditor - Chat")
        self.root.geometry("500x500")
        self.root.configure(bg="#1a1a2e")
        self.ws = None
        self.running = False
        self.username = None
        self.password = None
        self.cfg = load_config()
        self.build_login_screen()

    def clear(self):
        for w in self.root.winfo_children():
            w.destroy()

    def build_login_screen(self):
        self.clear()
        self.root.geometry("400x350")
        frame = tk.Frame(self.root, bg="#1a1a2e")
        frame.pack(expand=True)
        tk.Label(frame, text="WiFi Auditor Chat", font=("Arial", 18, "bold"), fg="#64B5F6", bg="#1a1a2e").pack(pady=(0, 20))
        tk.Label(frame, text="Servidor:", fg="white", bg="#1a1a2e", anchor="w").pack(fill="x", padx=40)
        self.server_entry = tk.Entry(frame, bg="#0f3460", fg="white", insertbackground="white", relief="flat")
        self.server_entry.pack(fill="x", padx=40, pady=(0, 10), ipady=4)
        self.server_entry.insert(0, self.cfg.get("server", "wifi-auditor.onrender.com:443"))
        self.status_lbl = tk.Label(frame, text="", fg="#FFB74D", bg="#1a1a2e")
        self.status_lbl.pack()
        btn_frame = tk.Frame(frame, bg="#1a1a2e")
        btn_frame.pack(pady=20)
        tk.Button(btn_frame, text="Conectar", bg="#FF9800", fg="white", relief="flat", padx=20, pady=5, command=self.connect_guest).pack(side="left", padx=5)
        tk.Button(btn_frame, text="Registrarse", bg="#1565C0", fg="white", relief="flat", padx=20, pady=5, command=self.show_register).pack(side="left", padx=5)
        tk.Button(btn_frame, text="Iniciar sesion", bg="#4CAF50", fg="white", relief="flat", padx=20, pady=5, command=self.show_login).pack(side="left", padx=5)

    def connect_guest(self):
        host = self.server_entry.get().strip()
        save_config(server=host)
        protocol = "wss" if ":443" in host else "ws"
        url = f"{protocol}://{host}"
        self.status_lbl.config(text="Conectando...")
        def run():
            try:
                ws = websocket.create_connection(url, timeout=30, ping_interval=25, ping_timeout=10)
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
        self.build_login_screen()

    def handle_message(self, msg):
        t = msg.get("type")
        if t == "waiting":
            self.status_lbl.config(text="Esperando otro dispositivo...")
        elif t == "paired":
            self.status_lbl.config(text="Conectado!")
            self.display_system("Conectado a otro dispositivo!")
        elif t == "peer_disconnected":
            self.status_lbl.config(text="El otro dispositivo se desconecto")
            self.display_system("El otro dispositivo se desconecto")
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
        top = tk.Frame(self.root, bg="#16213e")
        top.pack(fill="x")
        tk.Label(top, text="WiFi Auditor Chat", font=("Arial", 13, "bold"), fg="#64B5F6", bg="#16213e").pack(side="left", padx=12, pady=10)
        self.status_lbl = tk.Label(top, text="Conectado", fg="#4CAF50", bg="#16213e", font=("Arial", 9))
        self.status_lbl.pack(side="left", padx=10)
        tk.Button(top, text="Desconectar", bg="#f44336", fg="white", relief="flat", command=self.disconnect).pack(side="right", padx=10)
        self.chat_display = tk.Text(self.root, bg="#0f3460", fg="white", font=("Arial", 11), wrap="word", state="disabled", relief="flat")
        self.chat_display.pack(fill="both", expand=True, padx=10, pady=5)
        self.chat_display.tag_config("me", justify="right", foreground="#90CAF9")
        self.chat_display.tag_config("other", justify="left", foreground="#A5D6A7")
        self.chat_display.tag_config("system", justify="center", foreground="#FFB74D", font=("Arial", 9))
        self.chat_display.tag_config("time", foreground="#555555", font=("Arial", 8))
        inp = tk.Frame(self.root, bg="#1a1a2e")
        inp.pack(fill="x", padx=10, pady=(0, 10))
        self.msg_entry = tk.Entry(inp, bg="#0f3460", fg="white", insertbackground="white", relief="flat", font=("Arial", 11))
        self.msg_entry.pack(side="left", fill="x", expand=True, padx=(0, 5), ipady=8)
        self.msg_entry.bind("<Return>", lambda e: self.send_message())
        tk.Button(inp, text="Enviar", bg="#4CAF50", fg="white", relief="flat", padx=20, command=self.send_message).pack(side="right")

    def display_message(self, from_user, text, timestamp):
        self.chat_display.config(state="normal")
        if timestamp:
            t = datetime.fromtimestamp(timestamp / 1000).strftime("%H:%M")
            self.chat_display.insert(tk.END, f"  {t}  ", "time")
        tag = "me" if from_user == "Yo" else "other"
        self.chat_display.insert(tk.END, f"{from_user}: {text}\n", tag)
        self.chat_display.see(tk.END)
        self.chat_display.config(state="disabled")

    def send_message(self):
        text = self.msg_entry.get().strip()
        if not text: return
        self.msg_entry.delete(0, tk.END)
        self.display_message("Yo", text, int(datetime.now().timestamp() * 1000))
        self.send_raw({"type": "message", "text": text})

    def disconnect(self):
        self.running = False
        if self.ws:
            try: self.ws.close()
            except: pass
            self.ws = None
        self.build_login_screen()

if __name__ == "__main__":
    root = tk.Tk()
    app = DesktopClient(root)
    root.mainloop()
