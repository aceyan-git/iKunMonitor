import os
import platform
import re
import subprocess
import threading
import time
import tkinter as tk
from tkinter import ttk

import desktop_sampler

PACKAGE_NAME = "com.ikun.monitor"
VERSION_LABEL = "v0.1"
POLL_MS = 1000
APP_DISPLAY_NAME = "iKunMonitor"
ACTIVATOR_DISPLAY_NAME = "iKunMonitor Activator"


def _resource_path(*parts: str) -> str:
    base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, *parts)


def _logo_png_path() -> str | None:
    # Prefer rounded-corner asset if present.
    cands = [
        _resource_path("resources", "ikun_logo_rounded.png"),
        _resource_path("resources", "ikun_logo.png"),
    ]
    for p in cands:
        if os.path.exists(p):
            return p
    return None


def _set_app_icons(root: tk.Tk) -> tk.PhotoImage | None:
    # Cross-platform window/taskbar icon.
    # Note: packaged apps should also set native bundle icon (.icns/.ico) at build time.
    png = _logo_png_path()
    if not png:
        return None

    try:
        img = tk.PhotoImage(file=png)
        # iconphoto works on Windows/Linux; on macOS it typically affects Dock icon for Tk.
        root.iconphoto(True, img)
        return img
    except Exception:
        return None


def _load_header_icon(target_px: int = 18) -> tk.PhotoImage | None:
    png = _logo_png_path()
    if not png:
        return None

    try:
        img = tk.PhotoImage(file=png)
        w, h = int(img.width() or 0), int(img.height() or 0)
        m = max(w, h)
        if m <= 0:
            return img

        # Tk's subsample only supports integer scaling.
        factor = max(1, int(round(m / float(max(1, int(target_px))))))
        if factor > 1:
            img = img.subsample(factor, factor)
        return img
    except Exception:
        return None


def resolve_adb_path() -> str | None:
    # Hard requirement: adb must be embedded.
    # We do NOT auto-fallback to system adb.
    sys = (platform.system() or "").lower()

    if "windows" in sys:
        adb = _resource_path("resources", "platform-tools", "platform-Win", "adb.exe")
        if os.path.exists(adb):
            return adb

    else:
        arch = platform.machine().lower()
        if arch in ("arm64", "aarch64"):
            sub = "darwin_arm64"
        else:
            sub = "darwin_x86_64"

        adb = _resource_path("resources", "platform-tools", sub, "adb")
        if os.path.exists(adb) and os.access(adb, os.X_OK):
            return adb

    # allow dev override only if explicitly provided
    override = os.environ.get("IKUNMONITOR_ACTIVATOR_ADB_PATH", "").strip()
    if not override:
        override = os.environ.get("PERF_MONITOR_ACTIVATOR_ADB_PATH", "").strip()
    if override and os.path.exists(override) and os.access(override, os.X_OK):
        return override

    return None


def is_quarantined(path: str) -> bool:
    # macOS Gatekeeper quarantine flag
    try:
        p = subprocess.run(
            ["xattr", "-p", "com.apple.quarantine", path],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=1.5,
        )
        return p.returncode == 0
    except Exception:
        return False


class AdbError(Exception):
    pass


def run_adb(adb_path: str, args: list[str], timeout_s: float) -> str:
    try:
        p = subprocess.run(
            [adb_path, *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout_s,
        )
    except subprocess.TimeoutExpired as e:
        raise AdbError("adb 执行超时") from e
    except OSError as e:
        # Common on macOS when the binary is quarantined / blocked by Gatekeeper.
        raise AdbError("无法执行 adb（可能被系统拦截或无权限）") from e

    if p.returncode != 0:
        raw = (p.stderr or "").strip() or (p.stdout or "").strip()
        msg = raw or f"adb 执行失败（{p.returncode}）"
        cmd = "adb " + " ".join(args)
        raise AdbError(f"{msg}\n\n命令：{cmd}")

    return p.stdout or ""


# adb devices -l: usually serial + TAB + state
_DEVICE_LINE_RE = re.compile(r"^(?P<serial>\S+)\s+(?P<rest>.+)$")


def is_supported_serial(serial: str) -> bool:
    # Support USB / wireless debugging / emulator. Only block empty serials.
    return bool((serial or "").strip())


def parse_devices(devices_output: str) -> list[dict]:
    devices: list[dict] = []
    for raw in devices_output.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("List of devices"):
            continue

        m = _DEVICE_LINE_RE.match(line)
        if not m:
            continue

        serial = m.group("serial")
        rest = m.group("rest")
        tokens = rest.split()
        state = tokens[0] if tokens else "unknown"

        model = None
        for t in tokens[1:]:
            if t.startswith("model:"):
                model = t[len("model:") :].replace("_", " ")
                break

        devices.append({"serial": serial, "state": state, "model": model})

    return devices


def device_display_name(d: dict) -> str:
    base = d.get("model") or d.get("serial") or ""
    state = d.get("state")
    if state == "device":
        return base
    if state == "unauthorized":
        return f"{base}（未授权）"
    if state == "offline":
        return f"{base}（离线）"
    return f"{base}（未知）"


class ActivatorApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(ACTIVATOR_DISPLAY_NAME)
        self.root.geometry("620x560")
        self.root.minsize(520, 520)

        # Keep Tk images referenced to avoid garbage collection.
        self._window_icon_img: tk.PhotoImage | None = _set_app_icons(self.root)
        self._header_icon_img: tk.PhotoImage | None = None

        self.adb_path = resolve_adb_path()
        self.adb_error: str = ""
        self.adb_quarantined: bool = False

        if self.adb_path:
            self.adb_quarantined = is_quarantined(self.adb_path)
            try:
                _ = run_adb(self.adb_path, ["version"], timeout_s=2.0)
            except Exception as e:
                self.adb_error = str(e) or "ADB 异常"

        self.devices: list[dict] = []
        self.selected_serial: str | None = None
        self.is_activating = False
        self._activate_enabled: bool = False

        # Desktop sampler
        self.sampler_thread: threading.Thread | None = None
        self.sampler_stop: threading.Event | None = None
        self.sampler_serial: str | None = None
        self.sampler_detail: str = ""
        self.sampler_log_lines: list[str] = []

        self._sampler_mode: str = "idle"  # idle | waiting | active
        self._sampler_last_active_at: float = 0.0

        self._build_ui()
        self._refresh_ui_state()
        self._poll()

    # --- UI

    def _build_ui(self):
        self.root.configure(bg="#F6F7F9")

        outer = ttk.Frame(self.root, padding=12)
        outer.pack(fill=tk.BOTH, expand=True)

        header = ttk.Frame(outer)
        header.pack(fill=tk.X)

        # Title icon (same height as title font, as close as possible).
        self._header_icon_img = _load_header_icon(target_px=18)
        if self._header_icon_img is not None:
            ttk.Label(header, image=self._header_icon_img).pack(side=tk.LEFT, padx=(0, 6))

        ttk.Label(header, text=ACTIVATOR_DISPLAY_NAME, font=("SF Pro", 14, "bold")).pack(side=tk.LEFT)

        right = ttk.Frame(header)
        right.pack(side=tk.RIGHT)
        ttk.Label(right, text=PACKAGE_NAME, font=("Menlo", 11)).pack(side=tk.LEFT, padx=(0, 8))
        ttk.Label(right, text=VERSION_LABEL, font=("SF Pro", 11)).pack(side=tk.LEFT)

        # Device + action card
        self.device_card = ttk.Frame(outer, padding=12)
        self.device_card.pack(fill=tk.X, pady=(12, 10))
        ttk.Label(self.device_card, text="设备", font=("SF Pro", 12, "bold")).pack(anchor=tk.W)

        line = ttk.Frame(self.device_card)
        line.pack(fill=tk.X, pady=(10, 0))

        left = ttk.Frame(line)
        left.pack(side=tk.LEFT, fill=tk.X, expand=True)
        ttk.Label(left, text="当前设备", font=("SF Pro", 11), foreground="#666666").pack(anchor=tk.W)

        self.device_name_var = tk.StringVar(value="未连接")
        self.device_name_label = ttk.Label(left, textvariable=self.device_name_var, font=("SF Pro", 13, "bold"))
        self.device_name_label.pack(anchor=tk.W)

        self.combo_var = tk.StringVar(value="")
        self.device_combo = ttk.Combobox(left, textvariable=self.combo_var, state="readonly")
        self.device_combo.bind("<<ComboboxSelected>>", self._on_combo_selected)

        self.badge_var = tk.StringVar(value="未连接")
        self.badge_label = ttk.Label(line, textvariable=self.badge_var, font=("Menlo", 12))
        self.badge_label.pack(side=tk.RIGHT)

        # Main button (custom-painted; reliable colors)
        self.activate_btn = tk.Frame(self.device_card, bg="#E6E7EA")
        self.activate_btn.pack(fill=tk.X, pady=(12, 0))
        self.activate_btn_label = tk.Label(
            self.activate_btn,
            text="一 键 激 活",
            font=("SF Pro", 15, "bold"),
            bg="#E6E7EA",
            fg="#6B6B6B",
            pady=10,
        )
        self.activate_btn_label.pack(fill=tk.BOTH, expand=True)
        self.activate_btn.bind("<Button-1>", lambda _e: self._on_activate_clicked())
        self.activate_btn_label.bind("<Button-1>", lambda _e: self._on_activate_clicked())

        # Result
        self.result_frame = ttk.Frame(self.device_card, padding=10)
        self.result_frame.pack(fill=tk.X, pady=(10, 0))
        ttk.Label(self.result_frame, text="结果", font=("SF Pro", 11), foreground="#666666").pack(anchor=tk.W)

        self.result_var = tk.StringVar(value="")
        self.result_label = ttk.Label(self.result_frame, textvariable=self.result_var, font=("SF Pro", 13, "bold"))
        self.result_label.pack(anchor=tk.W, pady=(6, 0))

        # Failure details
        self.error_detail: str = ""
        self.error_title = ttk.Label(self.result_frame, text="失败原因", font=("SF Pro", 11), foreground="#666666")
        self.error_text = tk.Text(
            self.result_frame,
            height=5,
            wrap="word",
            relief=tk.FLAT,
            bg="#FFFFFF",
            fg="#333333",
            highlightthickness=1,
            highlightbackground="#E6E7EA",
        )
        self.error_text.configure(state=tk.DISABLED)

        self.error_actions = ttk.Frame(self.result_frame)
        self.copy_btn = ttk.Button(self.error_actions, text="复制失败原因", command=self._copy_error_detail)
        self.copy_btn.pack(side=tk.RIGHT)

        # Tips card
        self.tips_card = ttk.Frame(outer, padding=12)
        self.tips_card.pack(fill=tk.X)
        ttk.Label(self.tips_card, text="手机设置", font=("SF Pro", 12, "bold")).pack(anchor=tk.W)

        self.tip_vars: list[tk.StringVar] = [
            tk.StringVar(value="开启开发者选项"),
            tk.StringVar(value="开启 USB 调试"),
            tk.StringVar(value="连接数据线/无线调试，点击“允许 USB 调试/无线调试授权”"),
            tk.StringVar(value="在系统设置里为 iKunMonitor 开启悬浮窗权限"),
        ]
        for v in self.tip_vars:
            ttk.Label(self.tips_card, textvariable=v, font=("SF Pro", 12), foreground="#444444").pack(anchor=tk.W, pady=2)

        # Sampler card
        self.sampler_card = ttk.Frame(outer, padding=12)
        self.sampler_card.pack(fill=tk.X, pady=(10, 0))

        sampler_header = ttk.Frame(self.sampler_card)
        sampler_header.pack(fill=tk.X)
        ttk.Label(sampler_header, text="采集服务（ADB）", font=("SF Pro", 12, "bold")).pack(side=tk.LEFT)
        ttk.Button(sampler_header, text="复制日志", command=self._copy_sampler_detail).pack(side=tk.RIGHT)

        self.sampler_status_var = tk.StringVar(value="未启动")
        ttk.Label(self.sampler_card, textvariable=self.sampler_status_var, font=("Menlo", 12), foreground="#666666").pack(anchor=tk.W, pady=(6, 0))

        self.sampler_text = tk.Text(
            self.sampler_card,
            height=6,
            wrap="word",
            relief=tk.FLAT,
            bg="#FFFFFF",
            fg="#333333",
            highlightthickness=1,
            highlightbackground="#E6E7EA",
        )
        self.sampler_text.configure(state=tk.DISABLED)
        self.sampler_text.pack(fill=tk.X, pady=(8, 0))
        self._set_sampler_detail("等待")

    # --- device polling

    def _poll(self):
        self._scan_devices()
        self._refresh_ui_state()
        self.root.after(POLL_MS, self._poll)

    def _scan_devices(self):
        if not self.adb_path:
            self.adb_error = "ADB 缺失"
            self.devices = []
            return

        try:
            out = run_adb(self.adb_path, ["devices", "-l"], timeout_s=2.5)
            self.devices = parse_devices(out)
            self.adb_error = ""
        except Exception as e:
            self.devices = []
            self.adb_error = str(e) or "ADB 异常"

        serials = [d.get("serial") for d in self.devices]
        if self.selected_serial not in serials:
            pick = next((d for d in self.devices if d.get("state") == "device"), None) or (self.devices[0] if self.devices else None)
            self.selected_serial = (pick or {}).get("serial")

    def _selected_device(self) -> dict | None:
        if not self.devices:
            return None
        if self.selected_serial:
            for d in self.devices:
                if d.get("serial") == self.selected_serial:
                    return d
        return self.devices[0]

    # --- UI state

    def _show_combo(self, show: bool):
        if show:
            if not self.device_combo.winfo_ismapped():
                self.device_name_label.pack_forget()
                self.device_combo.pack(anchor=tk.W, fill=tk.X)
        else:
            if self.device_combo.winfo_ismapped():
                self.device_combo.pack_forget()
                self.device_name_label.pack(anchor=tk.W)

    def _set_button_state(self, enabled: bool):
        self._activate_enabled = bool(enabled)
        if enabled:
            self.activate_btn.configure(bg="#07C160")
            self.activate_btn_label.configure(bg="#07C160", fg="#FFFFFF")
            try:
                self.activate_btn.configure(cursor="hand2")
                self.activate_btn_label.configure(cursor="hand2")
            except Exception:
                pass
        else:
            self.activate_btn.configure(bg="#E6E7EA")
            self.activate_btn_label.configure(bg="#E6E7EA", fg="#6B6B6B")
            try:
                self.activate_btn.configure(cursor="arrow")
                self.activate_btn_label.configure(cursor="arrow")
            except Exception:
                pass

    def _refresh_ui_state(self):
        if not self.adb_path:
            self._stop_sampler()
            self.device_name_var.set("—")
            self.badge_var.set("ADB 缺失")
            self._set_button_state(enabled=False)
            return

        if self.adb_error:
            self._stop_sampler()
            self.device_name_var.set("—")
            self.badge_var.set("ADB 异常")
            self._set_button_state(enabled=False)
            self._show_combo(False)
            if self.adb_quarantined and self.adb_path and self.tip_vars:
                self.tip_vars[3].set("如 ADB 被系统拦截：终端执行 xattr -d com.apple.quarantine adb")
            return

        d = self._selected_device()
        if not d:
            self._stop_sampler()
            self.device_name_var.set("未连接")
            self.badge_var.set("未连接")
            self._set_button_state(enabled=False)
            self._show_combo(False)
            return

        # device name / dropdown
        if len(self.devices) <= 1:
            self._show_combo(False)
            self.device_name_var.set(device_display_name(d))
        else:
            self._show_combo(True)
            names = [device_display_name(x) for x in self.devices]
            self.device_combo["values"] = names
            idx = 0
            for i, x in enumerate(self.devices):
                if x.get("serial") == self.selected_serial:
                    idx = i
                    break
            self.device_combo.current(idx)

        state = d.get("state")
        serial = (d.get("serial") or "").strip()
        supported_ok = is_supported_serial(serial)

        if state == "device":
            self.badge_var.set("已连接")
        elif state == "unauthorized":
            self.badge_var.set("未授权")
        elif state == "offline":
            self.badge_var.set("离线")
        else:
            self.badge_var.set("未知")

        ready = (state == "device") and supported_ok and (not self.is_activating)
        self._set_button_state(enabled=ready)

        # desktop sampler auto-run only when connected
        if state == "device" and supported_ok:
            self._ensure_sampler_running(serial)
        else:
            self._stop_sampler()

    # --- error detail

    def _set_error_detail(self, detail: str):
        self.error_detail = (detail or "").strip()

        if not self.error_detail:
            if self.error_title.winfo_ismapped():
                self.error_title.pack_forget()
            if self.error_text.winfo_ismapped():
                self.error_text.pack_forget()
            if self.error_actions.winfo_ismapped():
                self.error_actions.pack_forget()
            return

        if not self.error_title.winfo_ismapped():
            self.error_title.pack(anchor=tk.W, pady=(10, 0))
        if not self.error_text.winfo_ismapped():
            self.error_text.pack(fill=tk.X, pady=(6, 0))
        if not self.error_actions.winfo_ismapped():
            self.error_actions.pack(fill=tk.X, pady=(6, 0))

        self.error_text.configure(state=tk.NORMAL)
        self.error_text.delete("1.0", tk.END)
        self.error_text.insert("1.0", self.error_detail)
        self.error_text.configure(state=tk.DISABLED)

    def _copy_error_detail(self):
        if not self.error_detail:
            return
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(self.error_detail)
        except Exception:
            pass

    # --- sampler

    def _stop_sampler(self):
        ev = self.sampler_stop
        self.sampler_stop = None
        self.sampler_serial = None
        if ev is not None:
            try:
                ev.set()
            except Exception:
                pass

        # IMPORTANT: keep logs for the current test round.
        # Only clear logs when phone side starts a NEW monitoring session.
        self.sampler_thread = None
        self._sampler_mode = "idle"
        self._sampler_last_active_at = 0.0
        self.sampler_status_var.set("未启动")
        self._set_sampler_detail("\n".join(self.sampler_log_lines) or "等待")

    def _ensure_sampler_running(self, serial: str):
        if not self.adb_path or not serial:
            return

        t = self.sampler_thread
        if t is not None and t.is_alive() and self.sampler_serial == serial:
            return

        self._stop_sampler()

        ev = threading.Event()
        self.sampler_stop = ev
        self.sampler_serial = serial
        self.sampler_status_var.set(f"等待 · {serial}")

        # Help debugging: show which desktop_sampler is actually imported.
        try:
            self._sampler_log(f"[OK] desktop_sampler={getattr(desktop_sampler, '__file__', '-')}")
        except Exception:
            pass

        def work():
            try:
                desktop_sampler.run_sampler_loop(
                    adb=self.adb_path,
                    serial=serial,
                    stop_event=ev,
                    log=self._sampler_log,
                )
            except Exception as e:
                self._sampler_log(str(e) or "采集服务异常")

        self.sampler_thread = threading.Thread(target=work, daemon=True)
        self.sampler_thread.start()

    def _set_sampler_detail(self, detail: str):
        self.sampler_detail = (detail or "").strip() or "等待"
        self.sampler_text.configure(state=tk.NORMAL)
        self.sampler_text.delete("1.0", tk.END)
        self.sampler_text.insert("1.0", self.sampler_detail)
        try:
            self.sampler_text.see(tk.END)
        except Exception:
            pass
        self.sampler_text.configure(state=tk.DISABLED)

    def _copy_sampler_detail(self):
        text = (self.sampler_detail or "").strip() or "等待"
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(text)
        except Exception:
            pass

    def _sampler_log(self, msg: str):
        # Keep key troubleshooting lines; avoid flicker.
        if not msg:
            return

        def apply():
            line = (msg or "").rstrip()
            if not line:
                return

            is_err = "[ERR]" in line
            is_warn = "[WARN]" in line
            is_wait = "[WAIT]" in line
            is_cfg = "[CFG]" in line
            is_active = is_cfg or ("[SAMPLE]" in line) or ("[WRITE]" in line) or ("[OK]" in line)

            # New monitoring round: only clear when phone side starts monitoring.
            # desktop_sampler prints: [CFG] enabled=True pkg=... keys=...
            if is_cfg and ("enabled=True" in line or "enabled=1" in line) and self._sampler_mode != "active":
                self.sampler_log_lines = []

            keep = is_wait or is_err or is_warn or is_cfg or ("[SAMPLE]" in line) or ("[WRITE]" in line) or ("[OK]" in line)
            if not keep:
                return

            self.sampler_log_lines.append(line)
            # Keep full log for single test round; cap to avoid unbounded memory.
            if len(self.sampler_log_lines) > 5000:
                self.sampler_log_lines = self.sampler_log_lines[-5000:]

            now = time.time()
            if is_active:
                self._sampler_last_active_at = now
                self._sampler_mode = "active"

            if is_err:
                self.sampler_status_var.set("异常")
            else:
                if (now - float(self._sampler_last_active_at or 0.0)) <= 2.0:
                    self.sampler_status_var.set("运行中")
                else:
                    self.sampler_status_var.set("等待")
                    self._sampler_mode = "waiting"

            # Keep full logs regardless of status.
            # Only clear logs when phone side starts a NEW monitoring session.
            self._set_sampler_detail("\n".join(self.sampler_log_lines) or "等待")

        self.root.after(0, apply)

    # --- events

    def _on_combo_selected(self, _evt=None):
        idx = self.device_combo.current()
        if 0 <= idx < len(self.devices):
            self.selected_serial = self.devices[idx].get("serial")
        self._refresh_ui_state()

    def _on_activate_clicked(self):
        if not self._activate_enabled:
            return

        d = self._selected_device()
        if not d or d.get("state") != "device" or not self.adb_path:
            return

        serial = d.get("serial")
        if not serial:
            return

        # clear previous
        self.result_var.set("")
        self._set_error_detail("")

        self.is_activating = True
        self._refresh_ui_state()

        def work():
            ok = False
            detail = ""
            try:
                out = run_adb(self.adb_path, ["-s", serial, "shell", "pm", "path", PACKAGE_NAME], timeout_s=6.0)
                if "package:" not in (out or ""):
                    raise AdbError("未检测到 iKunMonitor 已安装，请先安装 App 再激活。")

                # Keep as best-effort: some devices/ROM ignore it.
                run_adb(
                    self.adb_path,
                    ["-s", serial, "shell", "appops", "set", PACKAGE_NAME, "GET_USAGE_STATS", "allow"],
                    timeout_s=6.0,
                )
                ok = True
            except Exception as e:
                ok = False
                detail = str(e) or "激活失败（未知原因）"

            def done():
                self.is_activating = False
                if ok:
                    self.result_var.set("✓ 激活成功，可开始测试。")
                    self.result_label.configure(foreground="#07C160")
                    self._set_error_detail("")
                else:
                    self.result_var.set("激活失败，请重试")
                    self.result_label.configure(foreground="#FA5151")
                    self._set_error_detail(detail)
                self._refresh_ui_state()

            self.root.after(0, done)

        threading.Thread(target=work, daemon=True).start()


def main():
    root = tk.Tk()
    try:
        style = ttk.Style(root)
        _ = style.theme_use()
    except Exception:
        pass

    ActivatorApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
