"""FireDeck - PC server.

Serves the configuration page, the layout JSON, icon uploads, and executes
button actions on this PC.

Run:
    python deck_server.py --port 9100

Endpoints:
    GET  /                  configuration page
    GET  /layout            layout JSON (tablet polls this)
    GET  /press?page=P&id=N[&hold=1]
    GET  /icons/<file>      uploaded icon
    POST /layout            save layout JSON
    POST /icon?page=P&id=N  raw image upload (or &remove=1)
"""

import argparse
import json
import os
import platform
import subprocess
import threading
import time
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import psutil
from pynput.keyboard import Controller, Key

BASE = os.path.dirname(os.path.abspath(__file__))
LAYOUT_FILE = os.path.join(BASE, "layout.json")
CONFIG_FILE = os.path.join(BASE, "config.html")
ICONS_DIR = os.path.join(BASE, "icons")
os.makedirs(ICONS_DIR, exist_ok=True)

kb = Controller()
exec_lock = threading.Lock()
layout_lock = threading.Lock()

stats_lock = threading.Lock()
STATS = {}


def _lhm_temps():
    """Temps from LibreHardwareMonitor's web server on :8085, if running."""
    try:
        with urllib.request.urlopen("http://127.0.0.1:8085/data.json", timeout=1) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
    except Exception:
        return {}
    found = {}

    def walk(node):
        text = str(node.get("Text", ""))
        val = str(node.get("Value", ""))
        if val.endswith("°C"):
            try:
                found[text] = float(val.replace("°C", "").strip())
            except Exception:
                pass
        for child in (node.get("Children") or []):
            walk(child)

    walk(data)
    return found


def _nvidia():
    if not shutil_which("nvidia-smi"):
        return None
    try:
        out = subprocess.check_output(
            ["nvidia-smi",
             "--query-gpu=name,temperature.gpu,utilization.gpu,utilization.memory,"
             "memory.used,memory.total,power.draw,fan.speed,clocks.current.graphics",
             "--format=csv,noheader,nounits"],
            timeout=2, stderr=subprocess.DEVNULL).decode("utf-8", "replace").strip()
        p = [x.strip() for x in out.split(",")]

        def num(x):
            try:
                return float(x)
            except Exception:
                return None

        if len(p) >= 9:
            return {"name": p[0], "temp": num(p[1]), "pct": num(p[2]),
                    "mem_pct": num(p[3]), "mem_used": num(p[4]),
                    "mem_total": num(p[5]), "power": num(p[6]),
                    "fan": num(p[7]), "clock": num(p[8])}
    except Exception:
        pass
    return None


def shutil_which(name):
    from shutil import which
    return which(name)


def stats_loop():
    global STATS
    psutil.cpu_percent(None)
    psutil.cpu_percent(None, percpu=True)
    last_net = psutil.net_io_counters()
    last_t = time.time()
    boot = psutil.boot_time()
    lhm_cache = {}
    lhm_tick = 0.0
    gpu_cache = None
    gpu_tick = 0.0
    top_cache = []
    top_tick = 0.0
    cpu_name = platform.processor() or "CPU"

    while True:
        now = time.time()
        cpu = psutil.cpu_percent(None)
        per = psutil.cpu_percent(None, percpu=True)
        f = psutil.cpu_freq()
        vm = psutil.virtual_memory()
        du = psutil.disk_usage("C:\\")
        nc = psutil.net_io_counters()
        dt = max(0.001, now - last_t)
        up = (nc.bytes_sent - last_net.bytes_sent) / dt / 1e6
        down = (nc.bytes_recv - last_net.bytes_recv) / dt / 1e6
        last_net = nc
        last_t = now

        if now - gpu_tick > 2.0:
            gpu_cache = _nvidia()
            gpu_tick = now
        if now - lhm_tick > 5.0:
            lhm_cache = _lhm_temps()
            lhm_tick = now
        if now - top_tick > 5.0:
            procs = []
            skip = {"System Idle Process", "Idle", "System", "Registry", "MemCompression"}
            for p in psutil.process_iter(["name"]):
                try:
                    name = p.info.get("name") or "?"
                    if p.pid == 0 or name in skip:
                        continue
                    c = p.cpu_percent(None)
                    if c and c > 0.1:
                        procs.append((c, name))
                except Exception:
                    pass
            procs.sort(reverse=True)
            cores = psutil.cpu_count() or 1
            top_cache = [{"name": n, "cpu": c / cores} for c, n in procs[:3]]
            top_tick = now

        cpu_temp = None
        for key in ("CPU Package", "Core Max", "Core (Tctl/Tdie)", "CPU (Tctl/Tdie)"):
            if key in lhm_cache:
                cpu_temp = lhm_cache[key]
                break
        gpu_temp = None
        if gpu_cache:
            gpu_temp = gpu_cache.get("temp")
        if gpu_temp is None and "GPU Core" in lhm_cache:
            gpu_temp = lhm_cache["GPU Core"]

        data = {
            "time": now,
            "uptime": max(0, now - boot),
            "cpu": {
                "name": cpu_name,
                "cores": psutil.cpu_count(logical=True) or 0,
                "pct": cpu,
                "per": per,
                "freq": (f.current if f else None),
                "temp": cpu_temp,
            },
            "gpu": gpu_cache,
            "gpu_temp": gpu_temp,
            "ram": {"pct": vm.percent, "used": vm.used / 1e9, "total": vm.total / 1e9},
            "disk": {"pct": du.percent, "free": du.free / 1e9, "total": du.total / 1e9},
            "net": {"up": up, "down": down},
            "top": top_cache,
            "lhm": bool(lhm_cache),
        }
        with stats_lock:
            STATS = data
        time.sleep(1.0)


def btn(label, icon, color, kind, value, hold=None, image=""):
    b = {"label": label, "icon": icon, "color": color, "image": image,
         "type": kind, "value": value}
    if hold:
        b["hold"] = hold
    return b


DEFAULT_LAYOUT = {
    "version": 2,
    "pages": [
        {
            "name": "Media", "cols": 4, "rows": 3,
            "buttons": [
                btn("Mute", "X", "#455A64", "key", "media_volume_mute"),
                btn("Vol -", "-", "#455A64", "key", "media_volume_down"),
                btn("Vol +", "+", "#455A64", "key", "media_volume_up"),
                btn("Play/Pause", ">", "#37474F", "key", "media_play_pause"),
                btn("Previous", "|<", "#37474F", "key", "media_previous"),
                btn("Next", ">|", "#37474F", "key", "media_next"),
                btn("YouTube", "YT", "#B71C1C", "url", "https://youtube.com"),
                btn("Spotify", "SP", "#1B5E20", "url", "https://open.spotify.com"),
                btn("Twitch", "TW", "#4A148C", "url", "https://twitch.tv"),
                btn("Browser", "W", "#0D47A1", "url", "https://google.com"),
                btn("Fullscreen", "FS", "#263238", "key", "f11"),
                btn("Blank", "", "#212121", "key", ""),
            ],
        },
        {
            "name": "System", "cols": 4, "rows": 3,
            "buttons": [
                btn("Lock PC", "L", "#B71C1C", "hotkey", "win+l"),
                btn("Task View", "[]", "#1A237E", "hotkey", "win+tab"),
                btn("Screenshot", "S", "#1B5E20", "hotkey", "win+shift+s",
                    hold={"type": "launch", "value": "snippingtool"}),
                btn("Notepad", "N", "#4E342E", "launch", "notepad"),
                btn("Terminal", "$", "#212121", "command", "start cmd"),
                btn("Explorer", "E", "#0D47A1", "command", "explorer"),
                btn("Settings", "G", "#37474F", "command", "start ms-settings:"),
                btn("Copy", "C", "#263238", "hotkey", "ctrl+c"),
                btn("Paste", "V", "#263238", "hotkey", "ctrl+v"),
                btn("Undo", "U", "#263238", "hotkey", "ctrl+z"),
                btn("Volume Mixer", "VM", "#455A64", "command", "start ms-settings:apps-volume"),
                btn("PC Stats", "%", "#00695C", "stats", ""),
            ],
        },
        {
            "name": "Macros", "cols": 4, "rows": 3,
            "buttons": [
                btn("Volume Pulse", "~", "#455A64", "macro",
                    "key media_volume_up\ndelay 150\nkey media_volume_down"),
                btn("Open Notepad", "N", "#4E342E", "macro",
                    "launch notepad\ndelay 900\ntext Hello from FireDeck"),
                btn("Browser + Search", "W", "#0D47A1", "macro",
                    "launch https://google.com\ndelay 800"),
                btn("Mute + Lock", "ML", "#B71C1C", "macro",
                    "key media_volume_mute\ndelay 150\nhotkey win+l"),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
                btn("Blank", "", "#212121", "key", ""),
            ],
        },
        {
            "name": "Agents", "kind": "agents", "cols": 4, "rows": 3,
            "buttons": [],
        },
    ],
}

ALIASES = {
    "win": "cmd", "windows": "cmd", "super": "cmd",
    "control": "ctrl", "escape": "esc", "return": "enter",
    "del": "delete", "ins": "insert", "pgup": "page_up", "pgdn": "page_down",
}


def normalize_button(b):
    if not isinstance(b, dict):
        b = {}
    out = {
        "label": str(b.get("label", "")),
        "icon": str(b.get("icon", "")),
        "color": str(b.get("color", "#37474F")),
        "image": str(b.get("image", "")),
        "type": str(b.get("type", "key")),
        "value": b.get("value", ""),
    }
    hold = b.get("hold")
    if isinstance(hold, dict) and (hold.get("type") or hold.get("value")):
        out["hold"] = {"type": str(hold.get("type", "key")), "value": hold.get("value", "")}
    return out


def normalize_layout(data):
    if not isinstance(data, dict):
        data = {}
    pages_in = data.get("pages")
    if not isinstance(pages_in, list) or not pages_in:
        pages_in = [{
            "name": "Main",
            "cols": data.get("cols", 4),
            "rows": data.get("rows", 3),
            "buttons": data.get("buttons", []),
        }]
    pages = []
    for p in pages_in:
        if not isinstance(p, dict):
            continue
        cols = max(1, min(8, int(p.get("cols", 4) or 4)))
        rows = max(1, min(8, int(p.get("rows", 3) or 3)))
        items = [normalize_button(x) for x in (p.get("buttons") or [])]
        while len(items) < cols * rows:
            items.append(normalize_button({}))
        items = items[:cols * rows]
        pages.append({
            "name": str(p.get("name", "Page")),
            "kind": ("agents" if str(p.get("kind", "deck")).lower() == "agents" else "deck"),
            "cols": cols,
            "rows": rows,
            "buttons": items,
        })
    if not pages:
        pages = [{"name": "Main", "cols": 4, "rows": 3,
                  "buttons": [normalize_button({}) for _ in range(12)]}]
    return {"version": 2, "pages": pages}


def load_layout():
    with layout_lock:
        if not os.path.exists(LAYOUT_FILE):
            data = normalize_layout(DEFAULT_LAYOUT)
            with open(LAYOUT_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
            return data
        with open(LAYOUT_FILE, "r", encoding="utf-8") as f:
            return normalize_layout(json.load(f))


def save_layout(data):
    data = normalize_layout(data)
    with layout_lock:
        with open(LAYOUT_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    return data


def resolve_key(name):
    n = str(name).strip().lower()
    n = ALIASES.get(n, n)
    if len(n) == 1:
        return n
    return getattr(Key, n, None)


def run_step(step):
    kind = str(step.get("type") or "key").strip().lower()
    value = step.get("value", "")
    if kind == "delay":
        try:
            time.sleep(float(value) / 1000.0)
        except Exception:
            pass
    elif kind == "key":
        k = resolve_key(value)
        if k is not None:
            kb.press(k)
            kb.release(k)
    elif kind == "hotkey":
        parts = [p for p in str(value).split("+") if p.strip()]
        keys = [k for k in (resolve_key(p) for p in parts) if k is not None]
        for k in keys:
            kb.press(k)
        for k in reversed(keys):
            kb.release(k)
    elif kind == "text":
        kb.type(str(value))
    elif kind == "launch":
        subprocess.Popen(str(value), shell=False)
    elif kind == "url":
        webbrowser.open(str(value))
    elif kind == "command":
        subprocess.Popen(str(value), shell=True)
    else:
        print("  unknown step type:", kind)


def parse_macro(value):
    if isinstance(value, list):
        return value
    steps = []
    for line in str(value).splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if ":" in line and len(line.split(":", 1)[0].split()) == 1:
            t, rest = line.split(":", 1)
        else:
            parts = line.split(None, 1)
            t = parts[0]
            rest = parts[1] if len(parts) > 1 else ""
        steps.append({"type": t.strip(), "value": rest.strip()})
    return steps


def do_action(btn_def):
    kind = str(btn_def.get("type") or "key").strip().lower()
    if kind == "macro":
        steps = parse_macro(btn_def.get("value", ""))
    else:
        steps = [{"type": kind, "value": btn_def.get("value", "")}]
    for step in steps:
        print("   step:", step.get("type"), "=", step.get("value"))
        run_step(step)


AGENT_CONF = os.path.join(BASE, "agent.json")
agent_lock = threading.Lock()
AGENTS = {}
AGENT_ORDER = []
AGENT_STOPS = {}


def _mask(key):
    if not key:
        return ""
    return (key[:6] + "..." + key[-4:]) if len(key) > 12 else "****"


def _load_agent_conf(raw=None):
    """Return a list of {id,name,base,key} from raw data, env or agent.json."""
    if raw is None:
        base_env = os.environ.get("FIRE_DECK_AGENT_BASE", "").rstrip("/")
        key_env = os.environ.get("FIRE_DECK_AGENT_KEY", "")
        if base_env and key_env:
            return [{"id": "default", "name": "Agent", "base": base_env, "key": key_env}]
        if not os.path.exists(AGENT_CONF):
            return []
        try:
            with open(AGENT_CONF, "r", encoding="utf-8") as f:
                raw = json.load(f)
        except Exception:
            return []

    if isinstance(raw, list):
        items = raw
    elif isinstance(raw, dict) and isinstance(raw.get("agents"), list):
        items = raw["agents"]
    elif isinstance(raw, dict):
        items = [raw]
    else:
        items = []

    out = []
    used = set()
    for i, a in enumerate(items):
        if not isinstance(a, dict):
            continue
        base = str(a.get("base", "")).rstrip("/")
        if not base:
            continue
        aid = str(a.get("id") or "").strip() or ("agent%d" % (i + 1))
        while aid in used:
            aid += "_"
        used.add(aid)
        out.append({
            "id": aid,
            "name": str(a.get("name") or aid),
            "base": base,
            "key": str(a.get("key") or ""),
        })
    return out


def _save_agent_conf(items):
    with open(AGENT_CONF, "w", encoding="utf-8") as f:
        json.dump({"agents": items}, f, indent=2)


def _stop_agents():
    with agent_lock:
        stops = list(AGENT_STOPS.values())
    for ev in stops:
        ev.set()
    time.sleep(0.2)
    with agent_lock:
        AGENT_STOPS.clear()
        AGENTS.clear()
        del AGENT_ORDER[:]


def _start_agents():
    items = _load_agent_conf()
    with agent_lock:
        del AGENT_ORDER[:]
        for a in items:
            AGENT_ORDER.append(a["id"])
            AGENTS[a["id"]] = {"id": a["id"], "name": a["name"],
                               "online": False, "error": "starting..."}
            ev = threading.Event()
            AGENT_STOPS[a["id"]] = ev
            threading.Thread(target=agent_worker, args=(a, ev), daemon=True).start()
    return items


def restart_agents():
    _stop_agents()
    return _start_agents()


def _http_text(url, key, timeout=2.5):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + key})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def _parse_metrics(text):
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            name, val = line.rsplit(" ", 1)
            if "{" in name:
                name = name.split("{", 1)[0]
            out[name] = float(val)
        except Exception:
            pass
    return out


def _num(v):
    try:
        if v is None:
            return None
        return float(v)
    except Exception:
        return None


def _sys_summary(sysd):
    """Compact hardware summary from Unsloth Studio's /api/system."""
    if not isinstance(sysd, dict):
        return None
    cpu = sysd.get("cpu") or {}
    mem = sysd.get("memory") or {}
    disk = sysd.get("disk") or {}
    gpu = sysd.get("gpu") or {}
    devices = gpu.get("devices") or []

    total = _num(mem.get("total_gb")) or 0
    avail = _num(mem.get("available_gb")) or 0
    summary = {
        "cpu_pct": _num(cpu.get("usage_percent")),
        "ram_pct": _num(mem.get("percent_used")),
        "ram_used": round(max(0.0, total - avail), 1),
        "ram_total": total,
        "disk_pct": _num(disk.get("percent_used")),
        "disk_free": _num(disk.get("free_gb")),
        "uptime": _num(sysd.get("uptime_seconds")),
        "gpu_count": len(devices),
        "gpu": None,
    }
    if devices:
        def score(d):
            return (_num(d.get("vram_used_gb")) or 0,
                    _num(d.get("memory_total_gb")) or 0)
        primary = max(devices, key=score)
        summary["gpu"] = {
            "name": str(primary.get("name") or ""),
            "temp": _num(primary.get("temperature_c")),
            "util": _num(primary.get("gpu_utilization_pct")),
            "vram_used": _num(primary.get("vram_used_gb")),
            "vram_total": _num(primary.get("memory_total_gb")),
            "power": _num(primary.get("power_draw_w")),
        }
    return summary


def agent_worker(conf, stop_event):
    """Polls one llama.cpp / Unsloth Studio server for live agent telemetry."""
    aid = conf["id"]
    name = conf["name"]
    base = conf["base"]
    key = conf["key"]

    prev = {}
    prev_dec = {}
    prev_t = time.time()
    last_ctx = 0
    model = ""
    props_tick = 0.0
    sys_cache = None
    sys_tick = 0.0

    while not stop_event.is_set():
        now = time.time()
        try:
            met = _parse_metrics(_http_text(base + "/metrics", key))
            slots = json.loads(_http_text(base + "/slots", key))

            if now - props_tick > 5.0:
                try:
                    props = json.loads(_http_text(base + "/props", key))
                    model = str(props.get("model_path", model))
                except Exception:
                    pass
                props_tick = now

            if now - sys_tick > 2.0:
                try:
                    sys_cache = _sys_summary(
                        json.loads(_http_text(base + "/api/system", key, timeout=5)))
                except Exception:
                    pass
                sys_tick = now

            dt = max(0.2, now - prev_t)

            def delta(name):
                cur = met.get(name)
                old = prev.get(name)
                return max(0.0, cur - old) if (cur is not None and old is not None) else 0.0

            d_pred = delta("llamacpp:tokens_predicted_total")
            d_prompt = delta("llamacpp:prompt_tokens_total")
            processing = int(met.get("llamacpp:requests_processing", 0) or 0)
            deferred = int(met.get("llamacpp:requests_deferred", 0) or 0)

            used = 0
            ctx = 0
            busy = 0
            live_dec = 0
            for s in (slots or []):
                sid = s.get("id", 0)
                try:
                    c = int(s.get("n_ctx") or 0)
                    if c > ctx:
                        ctx = c
                except Exception:
                    pass
                if not s.get("is_processing"):
                    prev_dec.pop(sid, None)
                    continue
                busy += 1
                try:
                    nt = s.get("next_token") or {}
                    if isinstance(nt, list):
                        nt = nt[0] if nt else {}
                    if not isinstance(nt, dict):
                        nt = {}
                    nd = int(nt.get("n_decoded") or 0)
                    old = prev_dec.get(sid)
                    if old is not None and nd >= old:
                        live_dec += nd - old
                    prev_dec[sid] = nd
                    # in this build n_prompt_tokens tracks the full context length
                    pt = int(s.get("n_prompt_tokens") or 0)
                    if pt > used:
                        used = pt
                except Exception:
                    pass

            if used > 0:
                last_ctx = used
            shown_ctx = used if used > 0 else last_ctx

            tps = (live_dec / dt) if live_dec > 0 else (d_pred / dt)

            if processing > 0:
                state = "streaming" if live_dec > 0 else ("prompt" if d_prompt > 0 else "working")
            else:
                state = "idle"

            gen_total = met.get("llamacpp:tokens_predicted_total", 0)
            gen_secs = met.get("llamacpp:tokens_predicted_seconds_total", 0)
            avg = (gen_total / gen_secs) if gen_secs else 0
            gauge = met.get("llamacpp:predicted_tokens_seconds", 0) or 0

            data = {
                "id": aid,
                "name": name,
                "online": True,
                "ts": now,
                "state": state,
                "model": model,
                "tps": tps,
                "tps_avg": gauge if gauge else avg,
                "requests": {
                    "processing": processing,
                    "deferred": deferred,
                    "busy_slots": busy,
                    "slots": len(slots or []),
                },
                "context": {
                    "used": shown_ctx,
                    "total": ctx,
                    "pct": (100.0 * shown_ctx / ctx) if ctx else 0,
                },
                "tokens": {
                    "generated": gen_total,
                    "prompt": met.get("llamacpp:prompt_tokens_total", 0),
                    "cached": met.get("llamacpp:prompt_tokens_cached_total", 0),
                },
                "spec": {
                    "draft": met.get("llamacpp:spec_decode_num_draft_tokens_total", 0),
                    "accepted": met.get("llamacpp:spec_decode_num_accepted_tokens_total", 0),
                },
                "compactions": None,
                "sys": sys_cache,
            }
            prev = met
            prev_t = now
            with agent_lock:
                AGENTS[aid] = data
        except Exception as e:
            with agent_lock:
                AGENTS[aid] = {"id": aid, "name": name, "online": False, "error": str(e)}
        stop_event.wait(1.0)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/plain; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/":
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    self._send(200, f.read(), "text/html; charset=utf-8")
            except Exception as e:
                self._send(500, "config.html missing: %s" % e)
        elif path == "/layout":
            self._send(200, json.dumps(load_layout()), "application/json")
        elif path == "/stats":
            with stats_lock:
                self._send(200, json.dumps(STATS), "application/json")
        elif path == "/agents/config":
            items = _load_agent_conf()
            safe = [{
                "id": a["id"],
                "name": a["name"],
                "base": a["base"],
                "has_key": bool(a["key"]),
                "key_masked": _mask(a["key"]),
            } for a in items]
            self._send(200, json.dumps({"agents": safe}), "application/json")
        elif path == "/agents":
            with agent_lock:
                order = list(AGENT_ORDER)
                data = [AGENTS[i] for i in order if i in AGENTS]
            self._send(200, json.dumps({"agents": data}), "application/json")
        elif path == "/press":
            qs = parse_qs(parsed.query)
            try:
                page = int(qs.get("page", ["0"])[0])
                idx = int(qs.get("id", ["-1"])[0])
                hold = qs.get("hold", ["0"])[0] == "1"
            except Exception:
                self._send(400, "bad params")
                return
            layout = load_layout()
            pages = layout["pages"]
            if 0 <= page < len(pages) and 0 <= idx < len(pages[page]["buttons"]):
                b = pages[page]["buttons"][idx]
                if hold:
                    h = b.get("hold")
                    if h:
                        print("press page=%d id=%d HOLD %s -> %s %s"
                              % (page, idx, b.get("label"), h.get("type"), h.get("value")))
                        threading.Thread(target=do_action, args=(h,), daemon=True).start()
                        self._send(200, "ok")
                    else:
                        self._send(200, "no-hold")
                else:
                    print("press page=%d id=%d %s -> %s %s"
                          % (page, idx, b.get("label"), b.get("type"), b.get("value")))
                    threading.Thread(target=do_action, args=(b,), daemon=True).start()
                    self._send(200, "ok")
            else:
                self._send(404, "bad page/id")
        elif path.startswith("/icons/"):
            name = os.path.basename(path[len("/icons/"):])
            full = os.path.join(ICONS_DIR, name)
            if name and os.path.isfile(full):
                ext = os.path.splitext(name)[1].lower()
                ctype = {".png": "image/png", ".jpg": "image/jpeg",
                         ".jpeg": "image/jpeg", ".gif": "image/gif",
                         ".webp": "image/webp"}.get(ext, "application/octet-stream")
                with open(full, "rb") as f:
                    data = f.read()
                self._send(200, data, ctype, {"Cache-Control": "max-age=3600"})
            else:
                self._send(404, "no icon")
        elif path == "/favicon.ico":
            self._send(204, b"")
        else:
            self._send(404, "not found")

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/layout":
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            try:
                data = save_layout(json.loads(raw.decode("utf-8")))
                self._send(200, json.dumps({"ok": True, "pages": len(data["pages"])}),
                           "application/json")
            except Exception as e:
                self._send(400, "bad json: %s" % e)

        elif path == "/agents/config":
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            try:
                payload = json.loads(raw.decode("utf-8"))
                incoming = payload.get("agents", []) if isinstance(payload, dict) else []
                existing = {a["id"]: a for a in _load_agent_conf()}
                out = []
                used = set()
                for i, a in enumerate(incoming):
                    if not isinstance(a, dict):
                        continue
                    base = str(a.get("base", "")).rstrip("/")
                    if not base:
                        continue
                    aid = str(a.get("id") or "").strip() or ("agent%d" % (i + 1))
                    while aid in used:
                        aid += "_"
                    used.add(aid)
                    key = str(a.get("key") or "")
                    if not key:
                        key = existing.get(aid, {}).get("key", "")
                    out.append({
                        "id": aid,
                        "name": str(a.get("name") or aid),
                        "base": base,
                        "key": key,
                    })
                _save_agent_conf(out)
                restart_agents()
                print("agents updated:", ", ".join(a["name"] for a in out))
                self._send(200, json.dumps({"ok": True, "agents": len(out)}),
                           "application/json")
            except Exception as e:
                self._send(400, "bad json: %s" % e)

        elif path == "/icon":
            qs = parse_qs(parsed.query)
            try:
                page = int(qs.get("page", ["-1"])[0])
                idx = int(qs.get("id", ["-1"])[0])
            except Exception:
                self._send(400, "bad params")
                return
            length = int(self.headers.get("Content-Length", 0))
            data = self.rfile.read(length) if length else b""

            layout = load_layout()
            pages = layout["pages"]
            if not (0 <= page < len(pages) and 0 <= idx < len(pages[page]["buttons"])):
                self._send(404, "bad page/id")
                return

            button = pages[page]["buttons"][idx]

            if qs.get("remove", ["0"])[0] == "1":
                old = button.get("image", "")
                if old.startswith("/icons/"):
                    try:
                        os.remove(os.path.join(ICONS_DIR, os.path.basename(old)))
                    except Exception:
                        pass
                button["image"] = ""
                save_layout(layout)
                self._send(200, "")
                return

            if not data:
                self._send(400, "empty upload")
                return

            if data[:8] == b"\x89PNG\r\n\x1a\n":
                ext = ".png"
            elif data[:3] == b"\xff\xd8\xff":
                ext = ".jpg"
            elif data[:6] in (b"GIF87a", b"GIF89a"):
                ext = ".gif"
            elif data[:4] == b"RIFF" and data[8:12] == b"WEBP":
                ext = ".webp"
            else:
                self._send(400, "unsupported image")
                return

            name = "p%d_b%d_%d%s" % (page, idx, int(time.time() * 1000), ext)
            with open(os.path.join(ICONS_DIR, name), "wb") as f:
                f.write(data)

            old = button.get("image", "")
            button["image"] = "/icons/" + name
            save_layout(layout)
            if old.startswith("/icons/"):
                try:
                    os.remove(os.path.join(ICONS_DIR, os.path.basename(old)))
                except Exception:
                    pass
            print("icon uploaded:", name, len(data), "bytes")
            self._send(200, "/icons/" + name)

        else:
            self._send(404, "not found")

    def log_message(self, fmt, *args):
        p = getattr(self, "path", "")
        if p.startswith("/layout") or p.startswith("/agents") or p.startswith("/stats"):
            return
        print("%s - %s" % (self.address_string(), fmt % args))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9100)
    args = ap.parse_args()
    load_layout()
    threading.Thread(target=stats_loop, daemon=True).start()
    _start_agents()
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print("FireDeck server on http://0.0.0.0:%d" % args.port)
    print("Open http://localhost:%d to configure" % args.port)
    srv.serve_forever()


if __name__ == "__main__":
    main()
