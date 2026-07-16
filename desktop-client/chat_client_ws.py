#!/usr/bin/env python3
import json
import threading
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from datetime import datetime
import websocket
import sys, os, traceback

LOG = os.path.join(os.path.dirname(os.path.abspath(sys.executable if getattr(sys, 'frozen', False) else __file__)), "chat_debug.log")
def log(msg):
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(f"{datetime.now().strftime('%H:%M:%S')} {msg}\n")
    except: pass

class DesktopClient:
    def __init__(self, root):
        self.root = root
        self.root.title("WiFi Auditor - Chat")
        self.root.geometry("700x550")
        self.root.configure(bg="#1a1a2e")
        self.ws = None
        self.running = False
        self.username = None
        self.password = None
        self.my_id = None
        self.my_name = "Invitado"
        self.current_chat_id = None
        self.current_chat_name = None
        self.guests = []
        self.friends_list = []
        self.pending_list = []
        self.build_login_screen()

    def clear(self):
        for w in self.root.winfo_children():
            w.destroy()

    def build_login_screen(self):
        self.clear()
        self.root.geometry("400x400")
        frame = tk.Frame(self.root, bg="#1a1a2e")
        frame.pack(expand=True)
        tk.Label(frame, text="WiFi Auditor Chat", font=("Arial", 18, "bold"), fg="#64B5F6", bg="#1a1a2e").pack(pady=(0, 20))
        tk.Label(frame, text="Servidor:", fg="white", bg="#1a1a2e", anchor="w").pack(fill="x", padx=40)
        self.server_entry = tk.Entry(frame, bg="#0f3460", fg="white", insertbackground="white", relief="flat")
        self.server_entry.pack(fill="x", padx=40, pady=(0, 5), ipady=4)
        self.server_entry.insert(0, "wifi-auditor.onrender.com:443")
        tk.Label(frame, text="Apodo:", fg="white", bg="#1a1a2e", anchor="w").pack(fill="x", padx=40)
        self.name_entry = tk.Entry(frame, bg="#0f3460", fg="white", insertbackground="white", relief="flat")
        self.name_entry.pack(fill="x", padx=40, pady=(0, 10), ipady=4)
        self.name_entry.insert(0, "Invitado")
        self.status_lbl = tk.Label(frame, text="", fg="#FFB74D", bg="#1a1a2e")
        self.status_lbl.pack()
        btn_frame = tk.Frame(frame, bg="#1a1a2e")
        btn_frame.pack(pady=20)
        tk.Button(btn_frame, text="Conectar", bg="#FF9800", fg="white", relief="flat", padx=20, pady=5, command=self.connect_guest).pack(side="left", padx=5)
        tk.Button(btn_frame, text="Registrarse", bg="#1565C0", fg="white", relief="flat", padx=20, pady=5, command=self.show_register).pack(side="left", padx=5)
        tk.Button(btn_frame, text="Iniciar sesion", bg="#4CAF50", fg="white", relief="flat", padx=20, pady=5, command=self.show_login).pack(side="left", padx=5)

    def connect_guest(self):
        self.my_name = self.name_entry.get().strip() or "Invitado"
        self.connect_ws(self.server_entry.get().strip())

    def connect_ws(self, host):
        protocol = "wss" if ":443" in host else "ws"
        url = f"{protocol}://{host}"
        log(f"Connecting to {url}")
        self.status_lbl.config(text="Conectando...")
        def run():
            try:
                ws = websocket.create_connection(url, timeout=30)
                self.ws = ws
                self.running = True
                log("Connected OK")
                # Send name after connect
                ws.send(json.dumps({"type": "set_name", "name": self.my_name}))
                self.root.after(0, self.build_main_screen)
                threading.Thread(target=self.read_loop, daemon=True).start()
            except Exception as e:
                log(f"Connect error: {traceback.format_exc()}")
                self.root.after(0, lambda: self.status_lbl.config(text=f"Error: {e}"))
        threading.Thread(target=run, daemon=True).start()

    def connect_and_send(self, msg, login_after=False):
        host = self.server_entry.get().strip()
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
        log("Read loop started")
        while self.running and self.ws:
            try:
                raw = self.ws.recv()
                if raw is None:
                    log("recv returned None - connection closed")
                    break
                log(f"recv: {raw[:200]}")
                data = json.loads(raw)
                self.root.after(0, lambda m=data: self.handle_message(m))
            except Exception as e:
                log(f"Read loop error: {traceback.format_exc()}")
                try:
                    self.root.after(0, lambda: self.status_lbl.config(text=f"Error: {type(e).__name__}: {e}"))
                except:
                    pass
                break
        log("Read loop ended")
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
        if t == "init":
            self.my_id = msg.get("id")
            self.guests = msg.get("guests", [])
            self.update_guests_ui()
        elif t == "guest_list":
            self.guests = msg.get("guests", [])
            self.update_guests_ui()
        elif t == "waiting":
            self.status_lbl.config(text="Esperando otro dispositivo...")
        elif t == "paired":
            self.status_lbl.config(text="Conectado!")
            self.display_system("Conectado a otro dispositivo!")
        elif t == "peer_disconnected":
            self.status_lbl.config(text="El otro dispositivo se desconecto")
            self.display_system("El otro dispositivo se desconecto")
        elif t == "ok":
            self.status_lbl.config(text=msg.get("text", ""))
        elif t == "friends":
            self.friends_list = msg.get("list", [])
            self.update_friends_ui()
        elif t == "pending":
            self.pending_list = msg.get("list", [])
            self.update_pending_ui()
        elif t == "friend_request":
            a = messagebox.askyesno("Solicitud", f"{msg['from']} quiere ser tu amigo. Aceptar?")
            self.send_raw({"type": "friend_response", "from": msg["from"], "accept": a})
        elif t == "friend_request_accepted":
            messagebox.showinfo("Amigos", f"{msg['by']} acepto tu solicitud!")
            self.send_raw({"type": "get_friends"})
        elif t == "friend_online" or t == "friend_offline":
            self.send_raw({"type": "get_friends"})
        elif t == "message":
            sender = msg.get("from", "Invitado")
            from_id = msg.get("from_id", "")
            # If it's from the user we're chatting with, show in chat
            if self.current_chat_id and self.current_chat_id == from_id:
                self.display_message(sender, msg["text"], msg.get("timestamp", 0))
            elif not self.current_chat_id:
                # No specific chat selected, just show
                self.display_message(sender, msg["text"], 0)
            else:
                # Message from someone else - show notification
                self.status_lbl.config(text=f"Mensaje de {sender}")
                self.display_message(sender, msg["text"], 0)
        elif t == "messages":
            self.clear_chat_display()
            for m in msg.get("list", []):
                self.display_message(m["from_user"], m["text"], m["timestamp"])
        elif t == "error":
            messagebox.showerror("Error", msg.get("text", ""))

    def display_system(self, text):
        self.chat_display.config(state="normal")
        self.chat_display.insert(tk.END, f"  {text}\n", "system")
        self.chat_display.see(tk.END)
        self.chat_display.config(state="disabled")

    def build_main_screen(self):
        self.clear(); self.root.geometry("700x550")
        top = tk.Frame(self.root, bg="#16213e")
        top.pack(fill="x")
        tk.Label(top, text="WiFi Auditor Chat", font=("Arial", 13, "bold"), fg="#64B5F6", bg="#16213e").pack(side="left", padx=12, pady=10)
        self.status_lbl = tk.Label(top, text="Conectado", fg="#4CAF50", bg="#16213e", font=("Arial", 9))
        self.status_lbl.pack(side="left", padx=10)
        # Nickname change
        self.name_entry_top = tk.Entry(top, bg="#0f3460", fg="white", insertbackground="white", relief="flat", width=15)
        self.name_entry_top.insert(0, self.my_name)
        self.name_entry_top.pack(side="left", padx=5, ipady=2)
        tk.Button(top, text="Cambiar", bg="#FF9800", fg="white", relief="flat", font=("Arial", 8),
                  command=self.change_name).pack(side="left")
        tk.Button(top, text="Desconectar", bg="#f44336", fg="white", relief="flat", command=self.disconnect).pack(side="right", padx=10)
        if self.username:
            tk.Button(top, text="+ Amigo", bg="#FF9800", fg="white", relief="flat", command=self.add_friend_dialog).pack(side="right", padx=5)

        main = tk.Frame(self.root, bg="#1a1a2e")
        main.pack(fill="both", expand=True)

        # Left panel: guests + friends
        left = tk.Frame(main, bg="#16213e", width=200)
        left.pack(side="left", fill="y"); left.pack_propagate(False)
        tk.Label(left, text="Conectados", font=("Arial", 11, "bold"), fg="#90CAF9", bg="#16213e").pack(pady=(10, 5))
        self.guests_frame = tk.Frame(left, bg="#16213e")
        self.guests_frame.pack(fill="both", expand=True)
        tk.Label(left, text="Amigos", font=("Arial", 11, "bold"), fg="#A5D6A7", bg="#16213e").pack(pady=(15, 5))
        self.friends_frame = tk.Frame(left, bg="#16213e")
        self.friends_frame.pack(fill="x")
        tk.Label(left, text="Solicitudes", font=("Arial", 11, "bold"), fg="#FFB74D", bg="#16213e").pack(pady=(15, 5))
        self.pending_frame = tk.Frame(left, bg="#16213e")
        self.pending_frame.pack(fill="x")

        right = tk.Frame(main, bg="#1a1a2e")
        right.pack(side="right", fill="both", expand=True)
        self.chat_header = tk.Label(right, text="Selecciona un usuario", font=("Arial", 12), fg="#B0BEC5", bg="#1a1a2e")
        self.chat_header.pack(pady=5)
        self.chat_display = tk.Text(right, bg="#0f3460", fg="white", font=("Arial", 10), wrap="word", state="disabled", relief="flat", height=15)
        self.chat_display.pack(fill="both", expand=True, padx=5, pady=5)
        self.chat_display.tag_config("me", justify="right", foreground="#90CAF9")
        self.chat_display.tag_config("other", justify="left", foreground="#A5D6A7")
        self.chat_display.tag_config("system", justify="center", foreground="#FFB74D")
        self.chat_display.tag_config("time", foreground="#757575", font=("Arial", 8))
        inp = tk.Frame(right, bg="#1a1a2e")
        inp.pack(fill="x", padx=5, pady=(0, 10))
        self.msg_entry = tk.Entry(inp, bg="#0f3460", fg="white", insertbackground="white", relief="flat", font=("Arial", 10))
        self.msg_entry.pack(side="left", fill="x", expand=True, padx=(0, 5), ipady=8)
        self.msg_entry.bind("<Return>", lambda e: self.send_message())
        tk.Button(inp, text="Enviar", bg="#4CAF50", fg="white", relief="flat", padx=15, command=self.send_message).pack(side="right")
        self.update_guests_ui()

    def change_name(self):
        name = self.name_entry_top.get().strip()
        if name:
            self.my_name = name
            self.send_raw({"type": "set_name", "name": name})

    def update_guests_ui(self):
        if not hasattr(self, 'guests_frame'): return
        for w in self.guests_frame.winfo_children(): w.destroy()
        for g in self.guests:
            if g["id"] == self.my_id: continue
            active = "#4CAF50" if g["id"] == self.current_chat_id else "#0f3460"
            frame = tk.Frame(self.guests_frame, bg="#16213e")
            frame.pack(fill="x", padx=5, pady=2)
            tk.Button(frame, text=f"  {g['name']}", anchor="w", bg=active, fg="white", relief="flat",
                      font=("Arial", 10), command=lambda x=g: self.select_guest(x)).pack(fill="x")

    def select_guest(self, guest):
        self.current_chat_id = guest["id"]
        self.current_chat_name = guest["name"]
        self.chat_header.config(text=f"Chat con {guest['name']}")
        self.clear_chat_display()
        self.update_guests_ui()
        if self.current_chat_id:
            self.display_system(f"Hablando con {guest['name']}")

    def add_friend_dialog(self):
        u = simpledialog.askstring("Agregar amigo", "Nombre de usuario:")
        if u: self.send_raw({"type": "friend_request", "to": u})

    def update_friends_ui(self):
        if not hasattr(self, 'friends_frame'): return
        for w in self.friends_frame.winfo_children(): w.destroy()
        if self.friends_list:
            for f in self.friends_list:
                color = "#4CAF50" if f.get("online") else "#757575"
                frame = tk.Frame(self.friends_frame, bg="#16213e")
                frame.pack(fill="x", padx=5, pady=2)
                tk.Button(frame, text=f"  {f['username']}", anchor="w", bg="#0f3460", fg=color, relief="flat",
                          font=("Arial", 10), command=lambda u=f['username']: self.open_chat(u)).pack(fill="x")
        else:
            tk.Label(self.friends_frame, text="(sin amigos)", fg="#757575", bg="#16213e", font=("Arial", 9)).pack(padx=5, pady=10)

    def update_pending_ui(self):
        if not hasattr(self, 'pending_frame'): return
        for w in self.pending_frame.winfo_children(): w.destroy()
        for u in self.pending_list:
            frame = tk.Frame(self.pending_frame, bg="#16213e")
            frame.pack(fill="x", padx=5, pady=2)
            tk.Label(frame, text=u, fg="#FFB74D", bg="#16213e", font=("Arial", 9)).pack(side="left")
            tk.Button(frame, text="Aceptar", bg="#4CAF50", fg="white", relief="flat", font=("Arial", 8),
                      command=lambda x=u: self.accept_friend(x)).pack(side="right")

    def accept_friend(self, u):
        self.send_raw({"type": "friend_response", "from": u, "accept": True})

    def open_chat(self, username):
        self.current_chat_id = username
        self.current_chat_name = username
        self.chat_header.config(text=f"Chat con {username}")
        self.send_raw({"type": "get_messages", "with": username})
        self.update_guests_ui()

    def display_message(self, from_user, text, timestamp):
        self.chat_display.config(state="normal")
        if timestamp:
            t = datetime.fromtimestamp(timestamp / 1000).strftime("%H:%M")
            self.chat_display.insert(tk.END, f"  {t}  ", "time")
        tag = "me" if from_user == self.my_name else "other"
        self.chat_display.insert(tk.END, f"{from_user}: {text}\n", tag)
        self.chat_display.see(tk.END)
        self.chat_display.config(state="disabled")

    def clear_chat_display(self):
        self.chat_display.config(state="normal")
        self.chat_display.delete("1.0", tk.END)
        self.chat_display.config(state="disabled")

    def send_message(self):
        text = self.msg_entry.get().strip()
        if not text: return
        self.msg_entry.delete(0, tk.END)
        # Show locally
        ts = int(datetime.now().timestamp() * 1000)
        self.display_message(self.my_name, text, ts)
        if self.current_chat_id:
            # Send to specific guest
            self.send_raw({"type": "message", "to": self.current_chat_id, "text": text})
        else:
            # Broadcast to all
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
