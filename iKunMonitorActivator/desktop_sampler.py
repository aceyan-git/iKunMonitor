import json
import os
import platform
import re
import shutil
import subprocess
import threading
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, Future
from dataclasses import dataclass

PACKAGE_NAME = "com.ikun.monitor"

# Bump this when changing sampler behavior; used for debugging which code is running.
SAMPLER_REV = "2026-02-15-full-metrics-v15"

# Phone-side config/metrics file names
CONFIG_FILE_NAME = "pm_desktop_bridge_config.json"
METRICS_FILE_NAME = "pm_desktop_bridge_metrics.json"

# External app dir (historical approach)
CONFIG_DEVICE_PATH = f"/sdcard/Android/data/{PACKAGE_NAME}/files/{CONFIG_FILE_NAME}"
METRICS_DEVICE_PATH = f"/sdcard/Android/data/{PACKAGE_NAME}/files/{METRICS_FILE_NAME}"

# Internal app dir (robust on newer Android/emulator via `run-as`; requires debuggable build)
CONFIG_RUNAS_PATH = f"files/{CONFIG_FILE_NAME}"
METRICS_RUNAS_PATH = f"files/{METRICS_FILE_NAME}"


def _resource_path(*parts: str) -> str:
    base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, *parts)


def resolve_adb_path() -> str:
    sys = (platform.system() or "").lower()

    if "windows" in sys:
        adb = _resource_path("resources", "platform-tools", "platform-Win", "adb.exe")
        if os.path.exists(adb):
            return adb
        raise RuntimeError(f"找不到 adb：{adb}")

    # macOS
    arch = platform.machine().lower()
    if arch in ("arm64", "aarch64"):
        sub = "darwin_arm64"
    else:
        sub = "darwin_x86_64"

    adb = _resource_path("resources", "platform-tools", sub, "adb")
    if os.path.exists(adb) and os.access(adb, os.X_OK):
        return adb

    raise RuntimeError(f"找不到可执行 adb：{adb}（请确认已复制并 chmod +x）")


class AdbError(RuntimeError):
    pass


def run_adb(adb: str, args: list[str], timeout_s: float = 6.0, input_text: str | None = None) -> str:
    try:
        p = subprocess.run(
            [adb, *args],
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_s,
        )
    except subprocess.TimeoutExpired as e:
        raise AdbError("adb 执行超时") from e
    except OSError as e:
        raise AdbError("无法执行 adb") from e

    if p.returncode != 0:
        raw = (p.stderr or "").strip() or (p.stdout or "").strip()
        cmd = "adb " + " ".join(args)
        raise AdbError(f"{raw or 'adb 执行失败'}\n\n命令：{cmd}")

    return p.stdout or ""


def sh_double_quote(s: str) -> str:
    return '"' + (
        (s or "")
        .replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("`", "\\`")
    ) + '"'


def sh_single_quote(s: str) -> str:
    return "'" + ((s or "").replace("'", "'\\''")) + "'"


def sf_latency_candidates(layer: str, target_pkg: str) -> list[str]:
    cands: list[str] = []

    def add(x: str | None) -> None:
        s = (x or "").strip()
        if s and s not in cands:
            cands.append(s)

    base = (layer or "").strip()
    add(base)

    m = re.match(r"^(SurfaceView\[[^\]]+\])", base)
    if m:
        add(m.group(1))

    m2 = re.search(r"SurfaceView\[([^\]]+)\]", base)
    if m2:
        wa = m2.group(1).strip()
        add(wa)
        add(f"SurfaceView - {wa}#0")
        add(f"SurfaceView - {wa}")

    add(target_pkg)

    return cands[:8]


PERFETTO_TP_URL = "https://get.perfetto.dev/trace_processor"


def _platform_subdir() -> str:
    sys = (platform.system() or "").lower()
    arch = (platform.machine() or "").lower()
    if "windows" in sys:
        return "win"
    if "darwin" in sys or "mac" in sys:
        return "darwin_arm64" if arch in ("arm64", "aarch64") else "darwin_x86_64"
    return "linux"


def _tp_exe_names() -> list[str]:
    sys = (platform.system() or "").lower()
    if "windows" in sys:
        return ["trace_processor.exe", "trace_processor_shell.exe"]
    return ["trace_processor", "trace_processor_shell"]


def _tp_primary_user_install_path() -> str:
    home = os.path.expanduser("~")
    return os.path.join(home, ".ikunmonitor", "tools", "perfetto", _platform_subdir(), _tp_exe_names()[0])


def _tp_user_install_paths() -> list[str]:
    home = os.path.expanduser("~")
    base = os.path.join(home, ".ikunmonitor", "tools", "perfetto", _platform_subdir())
    return [os.path.join(base, n) for n in _tp_exe_names()]


def _tp_bundled_paths() -> list[str]:
    out: list[str] = []
    for n in _tp_exe_names():
        out.append(_resource_path("resources", "perfetto", _platform_subdir(), n))
        out.append(_resource_path("resources", "perfetto", n))
    return out


def resolve_trace_processor_shell() -> str | None:
    env_keys = [
        "PM_TRACE_PROCESSOR",
        "PM_TRACE_PROCESSOR_SHELL",
        "TRACE_PROCESSOR",
        "TRACE_PROCESSOR_SHELL",
        "PERFETTO_TRACE_PROCESSOR",
        "PERFETTO_TRACE_PROCESSOR_SHELL",
    ]
    for k in env_keys:
        p = (os.environ.get(k) or "").strip()
        if p and os.path.exists(p) and os.access(p, os.X_OK):
            return p

    for p2 in _tp_user_install_paths():
        if os.path.exists(p2) and os.access(p2, os.X_OK):
            return p2

    for bp in _tp_bundled_paths():
        if os.path.exists(bp) and os.access(bp, os.X_OK):
            return bp

    for n in _tp_exe_names():
        p3 = shutil.which(n)
        if p3:
            return p3

    return None


def ensure_trace_processor_shell_installed(
    logln: Callable[[str], None] | None = None,
    *,
    force: bool = False,
) -> str | None:
    if not force:
        existing = resolve_trace_processor_shell()
        if existing:
            return existing

    target = _tp_primary_user_install_path()
    os.makedirs(os.path.dirname(target), exist_ok=True)

    tmp = target + ".download"
    try:
        if logln:
            logln(f"[OK] 正在下载 trace_processor 到本机（首次仅需一次）：{PERFETTO_TP_URL}")

        if shutil.which("curl"):
            _run_host(["curl", "-L", "-o", tmp, PERFETTO_TP_URL], timeout_s=60.0)
        else:
            import urllib.request

            with urllib.request.urlopen(PERFETTO_TP_URL, timeout=60) as resp:
                data = resp.read()
            with open(tmp, "wb") as f:
                f.write(data)

        try:
            if not (platform.system() or "").lower().startswith("win"):
                os.chmod(tmp, 0o755)
        except Exception:
            pass

        os.replace(tmp, target)

        if logln:
            logln(f"[OK] trace_processor 已安装：{target}")

        return target if os.path.exists(target) else None
    except Exception as e:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass

        if logln:
            logln(
                "[ERR] 自动安装 trace_processor 失败。\n"
                "      你可以手动安装：\n"
                "      1) 终端执行：curl -L -o trace_processor https://get.perfetto.dev/trace_processor\n"
                "      2) chmod +x trace_processor\n"
                f"      3) 放到：{os.path.dirname(target)}\n"
                "      4) 或设置环境变量 PM_TRACE_PROCESSOR=...\n"
                f"      失败原因：{str(e) or 'unknown'}"
            )
        return None


def _run_host(cmd: list[str], timeout_s: float = 10.0, input_text: str | None = None) -> str:
    p = subprocess.run(
        cmd,
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout_s,
    )
    if p.returncode != 0:
        err = (p.stderr or "").strip() or (p.stdout or "").strip()
        raise RuntimeError(err or f"host command failed: {cmd}")
    return p.stdout or ""


def _ensure_traced_enabled(adb: str, serial: str) -> None:
    """Ensure traced (Perfetto daemon) is running on the device."""
    try:
        out = run_adb(adb, ["-s", serial, "shell", "getprop", "persist.traced.enable"], timeout_s=3.0).strip()
        if out != "1":
            run_adb(adb, ["-s", serial, "shell", "setprop", "persist.traced.enable", "1"], timeout_s=3.0)
            time.sleep(0.5)
    except Exception:
        pass


def _remove_remote_file(adb: str, serial: str, path: str) -> None:
    """Best-effort remove a file on the device."""
    try:
        run_adb(adb, ["-s", serial, "shell", "rm", "-f", path], timeout_s=3.0)
    except Exception:
        pass


def perfetto_capture_frame_timeline(adb: str, serial: str, remote_out: str, duration_ms: int) -> None:
    dur = max(800, int(duration_ms))
    cfg = (
        "buffers: {\n"
        "  size_kb: 32768\n"
        "  fill_policy: RING_BUFFER\n"
        "}\n"
        "data_sources: {\n"
        "  config {\n"
        "    name: \"android.surfaceflinger.frametimeline\"\n"
        "  }\n"
        "}\n"
        "data_sources: {\n"
        "  config {\n"
        "    name: \"android.surfaceflinger.frame\"\n"
        "  }\n"
        "}\n"
        "data_sources: {\n"
        "  config {\n"
        "    name: \"linux.ftrace\"\n"
        "    ftrace_config {\n"
        "      atrace_categories: \"view\"\n"
        "      atrace_categories: \"gfx\"\n"
        "    }\n"
        "  }\n"
        "}\n"
        f"duration_ms: {dur}\n"
        "write_into_file: true\n"
        "flush_period_ms: 500\n"
        f"file_write_period_ms: {max(500, dur // 2)}\n"
    )

    _remove_remote_file(adb, serial, remote_out)
    _ensure_traced_enabled(adb, serial)

    cmds = [
        f"perfetto --txt -c - -o {sh_single_quote(remote_out)}",
        f"cmd perfetto --txt -c - -o {sh_single_quote(remote_out)}",
    ]

    last_err: Exception | None = None
    for c in cmds:
        try:
            run_adb(
                adb,
                ["-s", serial, "shell", "sh", "-c", sh_double_quote(c)],
                timeout_s=dur / 1000.0 + 10.0,
                input_text=cfg,
            )
            return
        except Exception as e:
            last_err = e
            if _is_permission_denied(e):
                alt_out = f"/data/misc/perfetto-traces/pm_ft_{serial.replace(':', '_').replace('/', '_')}.perfetto-trace"
                _remove_remote_file(adb, serial, alt_out)
                try:
                    c_alt = c.replace(sh_single_quote(remote_out), sh_single_quote(alt_out))
                    run_adb(
                        adb,
                        ["-s", serial, "shell", "sh", "-c", sh_double_quote(c_alt)],
                        timeout_s=dur / 1000.0 + 10.0,
                        input_text=cfg,
                    )
                    try:
                        run_adb(adb, ["-s", serial, "shell", "cp", alt_out, remote_out], timeout_s=5.0)
                    except Exception:
                        try:
                            run_adb(adb, ["-s", serial, "shell", "ln", "-sf", alt_out, remote_out], timeout_s=3.0)
                        except Exception:
                            pass
                    return
                except Exception as e2:
                    last_err = e2
                break
            if not _is_no_such_file(e):
                break

    raise RuntimeError(str(last_err) or "perfetto/cmd perfetto 录制失败")


def _ftrace_slice_fps(
    tp_query: Callable[[str], str],
    target_pkg: str,
    duration_ms: int,
) -> tuple[float | None, str]:
    """Fallback: compute FPS from ftrace slice table when frame_timeline is unavailable."""
    pkg = (target_pkg or "").strip()

    try:
        out = tp_query(
            "select count(*) from sqlite_master where type='table' and name='slice';"
        )
        lines = [ln.strip().strip('"').strip("'") for ln in (out or "").splitlines() if ln.strip()]
        count_val = None
        for _line in lines:
            if _line.isdigit():
                count_val = int(_line)
                break
        if count_val is not None and count_val == 0:
            return None, "slice 表不存在"
    except Exception:
        return None, "slice 表查询失败"

    frame_markers = [
        "Choreographer#doFrame",
        "DrawFrame",
        "doFrame",
        "queueBuffer",
        "HIDL::IComposerClient::executeCommands_2_2",
    ]

    for marker in frame_markers:
        try:
            q = (
                f"select printf('%d|%d|%d', count(*), min(ts), max(ts)) "
                f"from slice where name = '{marker}';"
            )
            out = tp_query(q)
            m = re.search(r"(\d+)\|(\d+)\|(\d+)", out or "")
            if not m:
                continue
            cnt = int(m.group(1))
            min_ts = int(m.group(2))
            max_ts = int(m.group(3))
            if cnt <= 1:
                continue

            span_s = (max_ts - min_ts) / 1e9 if max_ts > min_ts else 0.0
            if span_s > 0:
                fps = cnt / span_s
            else:
                fps = cnt * 1000.0 / float(max(1, duration_ms))

            return fps, f"ftrace_slice marker={marker} count={cnt} spanS={span_s:.2f}"
        except Exception:
            continue

    return None, "ftrace slice 中无可识别的帧标记"


def perfetto_fps_from_trace(
    trace_processor: str,
    trace_path: str,
    target_pkg: str,
    duration_ms: int,
    *,
    layer_hint: str | None = None,
    layer_candidates: list[str] | None = None,
) -> tuple[float | None, str]:
    if not os.path.exists(trace_path):
        return None, "trace 文件不存在"

    def sql_quote(s: str) -> str:
        return "'" + (s or "").replace("'", "''") + "'"

    def _tp_query(sql: str) -> str:
        try:
            return _run_host([trace_processor, trace_path, "-Q", sql], timeout_s=20.0)
        except RuntimeError as e:
            if "unknown option" in str(e).lower() or "unrecognized" in str(e).lower():
                import tempfile
                with tempfile.NamedTemporaryFile(mode="w", suffix=".sql", delete=False) as f:
                    f.write(sql)
                    f.flush()
                    tmp = f.name
                try:
                    return _run_host([trace_processor, trace_path, "-q", tmp], timeout_s=20.0)
                finally:
                    try:
                        os.remove(tmp)
                    except Exception:
                        pass
            raise

    def query_c_min_max(where_sql: str) -> tuple[int, int, int] | None:
        if "ts" in cols:
            q = f"select printf('%d|%d|%d', count(*), min(ts), max(ts)) from {table}{where_sql};"
        else:
            q = f"select printf('%d|0|0', count(*)) from {table}{where_sql};"
        out = _tp_query(q)
        m = re.search(r"(\d+)\|(\d+)\|(\d+)", out or "")
        if not m:
            return None
        return int(m.group(1)), int(m.group(2)), int(m.group(3))

    q_tables = (
        "select name from sqlite_master "
        "where (type='table' or type='view') and "
        "(name like '%frame%timeline%' or name like '%frame_slice%' or name like '%android_frames%');"
    )
    out_tables = _tp_query(q_tables)
    raw_tables = [ln.strip() for ln in (out_tables or "").splitlines() if ln.strip()]
    tables: list[str] = []
    for _rt in raw_tables:
        _cleaned = _rt.strip().strip('"').strip("'").strip()
        if not _cleaned:
            continue
        if _cleaned.lower() == "name" or _cleaned.startswith("-"):
            continue
        tables.append(_cleaned)
    if not tables:
        fps_ftrace, detail_ftrace = _ftrace_slice_fps(
            _tp_query, target_pkg, duration_ms
        )
        if fps_ftrace is not None:
            return fps_ftrace, detail_ftrace
        return None, "trace_processor 未发现 frame_timeline 表（可能数据源未生效/系统不支持）"

    prefer = [
        "actual_frame_timeline_slice",
        "frame_timeline_slice",
        "android_frame_timeline_slice",
        "expected_frame_timeline_slice",
    ]
    table = next((t for t in prefer if t in tables), tables[0])

    out_cols = _tp_query(f"pragma table_info({table});")
    cols: list[str] = []
    for ln in (out_cols or "").splitlines():
        parts = [p.strip().strip('"').strip("'") for p in ln.split("|")]
        if len(parts) >= 2 and parts[1] and parts[1].lower() != "name":
            cols.append(parts[1])

    pkg = (target_pkg or "").strip()

    filters: list[tuple[str, str, str]] = []

    if "layer_name" in cols:
        cands: list[str] = []
        for x in [layer_hint] + (layer_candidates or []):
            s = (x or "").strip()
            if s and s not in cands:
                cands.append(s)

        for c in cands[:10]:
            filters.append(("layer_name", f" where layer_name = {sql_quote(c)}", f"layer_eq={c}"))
        for c in cands[:10]:
            filters.append(("layer_name", f" where layer_name like {sql_quote('%' + c + '%')}", f"layer_like={c}"))

        if pkg:
            filters.append(("layer_name", f" where layer_name like {sql_quote('%' + pkg + '%')}", f"pkg_like={pkg}"))

    elif "name" in cols and pkg:
        filters.append(("name", f" where name like {sql_quote('%' + pkg + '%')}", f"name_like={pkg}"))

    filters.append(("-", "", "unfiltered"))

    unfiltered_stats: tuple[int, int, int] | None = None
    best: tuple[float, str] | None = None

    for key, where_sql, human in filters:
        stats = query_c_min_max(where_sql)
        if stats is None:
            continue
        cnt, min_ts, max_ts = stats

        if where_sql == "":
            unfiltered_stats = stats

        if cnt <= 0:
            continue

        span_s = 0.0
        if max_ts > min_ts and min_ts > 0:
            span_s = (max_ts - min_ts) / 1e9

        if span_s > 0:
            fps = cnt / span_s
            span_ms = int(span_s * 1000.0)
            detail = f"table={table} filter={human} count={cnt} spanMs={span_ms}"
        else:
            fps = cnt * 1000.0 / float(max(1, int(duration_ms)))
            detail = f"table={table} filter={human} count={cnt} durMs={duration_ms}"

        best = (fps, detail)
        break

    if best is None:
        if unfiltered_stats is not None:
            cnt0, _, _ = unfiltered_stats
            return None, f"frame_timeline 计数为 0（未过滤总计={cnt0}）"
        return None, "trace_processor 查询失败：无法得到 frame_timeline 统计"

    return best[0], best[1]


def _is_no_such_file(err: Exception) -> bool:
    s = (str(err) or "")
    return "No such file or directory" in s or "not found" in s.lower()


def _is_permission_denied(err: Exception) -> bool:
    s = (str(err) or "")
    return "Permission denied" in s or "errno: 13" in s


# ---------------------------------------------------------------------------
#  Additional FPS data sources (for ROMs where gfxinfo/SurfaceFlinger latency fail)
# ---------------------------------------------------------------------------

def run_as(adb: str, serial: str, args: list[str], timeout_s: float = 6.0, input_text: str | None = None) -> str:
    return run_adb(adb, ["-s", serial, "shell", "run-as", PACKAGE_NAME, *args], timeout_s=timeout_s, input_text=input_text)


def _ts() -> str:
    return time.strftime("%H:%M:%S")


def _is_run_as_not_debuggable(err: Exception) -> bool:
    s = (str(err) or "").lower()
    return "not debuggable" in s or "run-as" in s and "debug" in s and "not" in s


_DEVICE_LINE_RE = re.compile(r"^(?P<serial>\S+)\s+(?P<rest>.+)$")


def is_supported_serial(serial: str) -> bool:
    return bool((serial or "").strip())


def pick_default_serial(adb: str) -> str:
    out = run_adb(adb, ["devices", "-l"], timeout_s=3.0)
    serials: list[tuple[str, str]] = []
    for raw in out.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices"):
            continue
        m = _DEVICE_LINE_RE.match(line)
        if not m:
            continue
        serial = m.group("serial")
        rest = m.group("rest")
        tokens = rest.split()
        state = tokens[0] if tokens else "unknown"
        serials.append((serial, state))

    devices = [s for s, st in serials if st == "device"]
    if not devices:
        raise RuntimeError("未检测到 device 状态的设备（请插线/授权 USB 调试）")

    supported_devices = [s for s in devices if is_supported_serial(s)]
    if not supported_devices:
        raise RuntimeError(
            "未检测到可用设备。\n\n"
            "已检测到的设备：{}".format(", ".join(devices))
        )

    return supported_devices[0]


def _parse_proc_stat_ticks(line: str) -> int | None:
    r = line.rfind(")")
    if r <= 0:
        return None
    after = line[r + 1 :].strip()
    parts = after.split()
    if len(parts) <= 12:
        return None
    utime = int(parts[11])
    stime = int(parts[12])
    return utime + stime


def _parse_pss_kb(dumpsys: str) -> int | None:
    m = re.search(r"TOTAL\s+PSS:\s*(\d+)", dumpsys)
    if m:
        return int(m.group(1))
    m2 = re.search(r"\bTOTAL:\s*(\d+)", dumpsys)
    if m2:
        return int(m2.group(1))
    return None


def _parse_gfx_totals(dumpsys: str) -> tuple[int | None, int | None]:
    total = None
    janky = None
    m = re.search(r"Total\s+frames\s+rendered:\s*(\d+)", dumpsys)
    if m:
        total = int(m.group(1))
    m2 = re.search(r"Janky\s+frames:\s*(\d+)", dumpsys)
    if m2:
        janky = int(m2.group(1))
    return total, janky


@dataclass
class GfxState:
    prev_total: int | None = None
    prev_janky: int | None = None
    prev_t_ms: int | None = None
    no_frame_streak: int = 0
    fail_streak: int = 0
    disabled: bool = False


@dataclass
class SfLatencyState:
    layer: str | None = None
    prev_max_present_ns: int | None = None
    prev_t_ms: int | None = None
    last_layer_update_ms: int = 0
    no_frame_streak: int = 0
    fail_streak: int = 0
    disabled: bool = False


# ---------------------------------------------------------------------------
# Atrace ftrace text parsing helpers
# ---------------------------------------------------------------------------

# Regex: match lines with timestamp + tracing_mark_write frame markers
# atrace output line format:
#   <process>-<tid> (<tgid>) [<cpu>] .... <timestamp>: <event>
# or older:
#   <process>-<tid>  [<cpu>] .... <timestamp>: <event>
_ATRACE_MARK_RE = re.compile(
    r'^\s*\S+-\d+\s+(?:\(\s*\d+\s*\)\s+)?\[\d+\]\s+\S+\s+'
    r'(\d+\.\d+):\s+tracing_mark_write:\s+[BC]\|(\d+)\|(.+)',
    re.MULTILINE,
)

# Frame boundary markers — these indicate one frame of rendering work.
_FRAME_MARKERS = frozenset({
    "queueBuffer",
    "eglSwapBuffers",
    "eglSwapBuffersWithDamageKHR",
})

# VSYNC / composition events from SurfaceFlinger
_VSYNC_RE = re.compile(
    r'^\s*\S+-\d+\s+(?:\(\s*\d+\s*\)\s+)?\[\d+\]\s+\S+\s+'
    r'(\d+\.\d+):\s+.*\b(?:HW_VSYNC_ON_0|doComposition|postComposition)\b',
    re.MULTILINE,
)


def _count_frame_events(dump_text: str, since_ts: float) -> tuple[int, float, float, str]:
    """Parse atrace text output and count frame events after `since_ts`.

    Returns (frame_count, min_ts, max_ts, method).
    """
    timestamps: list[float] = []
    method = "unknown"

    # Method A: tracing_mark_write frame markers (queueBuffer, eglSwapBuffers)
    for m in _ATRACE_MARK_RE.finditer(dump_text):
        ts = float(m.group(1))
        marker = m.group(3).strip()
        if marker in _FRAME_MARKERS and ts > since_ts:
            timestamps.append(ts)

    if timestamps:
        method = "atrace_marker"
    else:
        # Method B: VSYNC/composition events
        for m in _VSYNC_RE.finditer(dump_text):
            ts = float(m.group(1))
            if ts > since_ts:
                timestamps.append(ts)
        if timestamps:
            method = "atrace_vsync"

    if not timestamps:
        return 0, 0.0, 0.0, "no_events"

    # De-duplicate close timestamps (within 2ms) to avoid double-counting
    timestamps.sort()
    deduped: list[float] = [timestamps[0]]
    for t in timestamps[1:]:
        if t - deduped[-1] > 0.002:
            deduped.append(t)

    return len(deduped), deduped[0], deduped[-1], method


class StreamingFpsBgWorker:
    """Background thread that streams atrace/ftrace data to compute FPS every ~1 second.

    Unlike offline Perfetto (capture->pull->analyze = 3-5s per cycle), this uses
    atrace --async_start + periodic atrace --async_dump to get frame events
    incrementally, just like PerfDog / GameBench.

    Falls back to Perfetto offline mode if atrace is not available.
    """

    def __init__(
        self,
        adb: str,
        serial: str,
        logln: Callable[[str], None],
    ):
        self._adb = adb
        self._serial = serial
        self._logln = logln

        self._lock = threading.Lock()
        self._latest_fps: float | None = None
        self._latest_fps_at_ms: int = 0
        self._latest_detail: str = ""
        self._sample_count: int = 0

        self._target_pkg: str = ""
        self._layer_hint: str = ""
        self._layer_candidates: list[str] = []
        self._sampling_ms: int = 1000
        self._enabled = False

        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._mode: str = ""
        self._tp: str | None = None

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="fps-bg")
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=15.0)
            self._thread = None

    def configure(
        self,
        target_pkg: str,
        layer_hint: str,
        layer_candidates: list[str],
        sampling_ms: int,
        enabled: bool,
    ) -> None:
        self._target_pkg = target_pkg
        self._layer_hint = layer_hint
        self._layer_candidates = list(layer_candidates)
        self._sampling_ms = sampling_ms
        self._enabled = enabled

    def read_latest(self) -> tuple[float | None, int, str]:
        with self._lock:
            return self._latest_fps, self._latest_fps_at_ms, self._latest_detail

    @property
    def sample_count(self) -> int:
        with self._lock:
            return self._sample_count

    # ------------------------------------------------------------------
    # atrace streaming
    # ------------------------------------------------------------------

    def _atrace_start(self) -> bool:
        """Start async atrace with gfx+view categories."""
        try:
            run_adb(
                self._adb,
                ["-s", self._serial, "shell", "atrace", "--async_start", "-b", "8192",
                 "-c", "gfx", "view"],
                timeout_s=5.0,
            )
            return True
        except Exception as e:
            self._logln(f"[WARN] atrace --async_start failed: {e}")
            return False

    def _atrace_stop(self) -> None:
        try:
            run_adb(
                self._adb,
                ["-s", self._serial, "shell", "atrace", "--async_stop"],
                timeout_s=5.0,
            )
        except Exception:
            pass

    def _atrace_dump(self) -> str:
        """Dump current atrace buffer (non-destructive read)."""
        return run_adb(
            self._adb,
            ["-s", self._serial, "shell", "atrace", "--async_dump", "-b", "8192",
             "-c", "gfx", "view"],
            timeout_s=5.0,
        )

    # ------------------------------------------------------------------
    # Perfetto offline fallback
    # ------------------------------------------------------------------

    def _perfetto_once(self) -> tuple[float | None, str]:
        """Single Perfetto offline capture + analysis."""
        tp = self._tp
        if not tp:
            tp = resolve_trace_processor_shell()
            if not tp:
                tp = ensure_trace_processor_shell_installed(logln=self._logln)
            self._tp = tp
        if not tp:
            return None, "trace_processor 不可用"

        target_pkg = self._target_pkg
        layer_hint = self._layer_hint
        layer_cands = list(self._layer_candidates)

        serial_safe = (self._serial or "").replace(":", "_").replace("/", "_")
        remote = f"/data/local/tmp/pm_ft_{serial_safe}.perfetto-trace"
        local = os.path.join("/tmp", f"pm_ft_{serial_safe}.perfetto-trace")

        dur_ms = 1500

        perfetto_capture_frame_timeline(
            self._adb, self._serial, remote_out=remote, duration_ms=dur_ms
        )
        run_adb(self._adb, ["-s", self._serial, "pull", remote, local], timeout_s=12.0)

        return perfetto_fps_from_trace(
            tp, local,
            target_pkg=target_pkg,
            duration_ms=dur_ms,
            layer_hint=layer_hint,
            layer_candidates=layer_cands,
        )

    # ------------------------------------------------------------------
    # Main loop
    # ------------------------------------------------------------------

    def _loop(self) -> None:
        while not self._stop_event.is_set():
            if self._enabled and self._target_pkg:
                break
            self._stop_event.wait(0.5)

        if self._stop_event.is_set():
            return

        atrace_ok = self._atrace_start()
        if atrace_ok:
            self._mode = "atrace"
            self._logln("[OK] FPS 后台线程：使用 atrace 流式采集（类 PerfDog 模式，1秒1次）")
        else:
            self._mode = "perfetto"
            self._logln("[INFO] FPS 后台线程：atrace 不可用，降级 Perfetto 离线模式")

        try:
            if self._mode == "atrace":
                self._loop_atrace()
            else:
                self._loop_perfetto()
        finally:
            if self._mode == "atrace":
                self._atrace_stop()

    def _loop_atrace(self) -> None:
        """Stream atrace data, compute FPS every ~1s."""
        last_max_ts = 0.0
        consecutive_empty = 0

        while not self._stop_event.is_set():
            if not self._enabled or not self._target_pkg:
                self._stop_event.wait(1.0)
                continue

            self._stop_event.wait(1.0)
            if self._stop_event.is_set():
                return

            try:
                dump = self._atrace_dump()
            except Exception as e:
                self._logln(f"[WARN] atrace dump failed: {e}")
                consecutive_empty += 1
                if consecutive_empty >= 5:
                    self._logln("[INFO] atrace 连续失败，切换 Perfetto 模式")
                    self._atrace_stop()
                    self._mode = "perfetto"
                    self._loop_perfetto()
                    return
                continue

            frame_count, min_ts, max_ts, method = _count_frame_events(
                dump, since_ts=last_max_ts
            )

            now_ms = int(time.time() * 1000)

            if frame_count > 0 and max_ts > min_ts:
                span_s = max_ts - min_ts
                if span_s > 0.05:
                    fps = frame_count / span_s
                else:
                    fps = float(frame_count)
                last_max_ts = max_ts
                consecutive_empty = 0

                with self._lock:
                    self._latest_fps = fps
                    self._latest_fps_at_ms = now_ms
                    self._latest_detail = f"{method} frames={frame_count} span={span_s:.3f}s"
                    self._sample_count += 1

                self._logln(f"[OK] FPS(atrace): {fps:.1f} ({method} frames={frame_count})")
            elif frame_count > 0:
                fps = float(frame_count)
                last_max_ts = max_ts if max_ts > 0 else last_max_ts
                consecutive_empty = 0

                with self._lock:
                    self._latest_fps = fps
                    self._latest_fps_at_ms = now_ms
                    self._latest_detail = f"{method} frames={frame_count} span=~1s"
                    self._sample_count += 1

                self._logln(f"[OK] FPS(atrace): {fps:.1f} ({method} frames={frame_count} span=~1s)")
            else:
                consecutive_empty += 1
                if consecutive_empty >= 10:
                    self._logln("[INFO] atrace 连续10次无帧事件，切换 Perfetto 模式")
                    self._atrace_stop()
                    self._mode = "perfetto"
                    self._loop_perfetto()
                    return

    def _loop_perfetto(self) -> None:
        """Perfetto offline fallback loop (slower, ~3-5s per sample)."""
        while not self._stop_event.is_set():
            if not self._enabled or not self._target_pkg:
                self._stop_event.wait(1.0)
                continue

            try:
                fps, detail = self._perfetto_once()
                now_ms = int(time.time() * 1000)

                with self._lock:
                    if fps is not None and fps >= 0:
                        self._latest_fps = fps
                        self._latest_fps_at_ms = now_ms
                        self._latest_detail = detail
                    self._sample_count += 1

                if fps is not None and fps >= 0:
                    self._logln(f"[OK] FPS(perfetto): {fps:.1f} ({detail})")
                else:
                    self._logln(f"[WARN] Perfetto 未能计算 FPS: {detail}")
            except Exception as e:
                self._logln(f"[ERR] Perfetto failed: {str(e)[:200]}")
                self._stop_event.wait(2.0)
                continue

            self._stop_event.wait(0.3)


@dataclass
class CpuState:
    prev_ticks: int | None = None
    prev_t_ms: int | None = None


@dataclass
class CpuTotalState:
    prev_total: int | None = None
    prev_idle: int | None = None


def _parse_battery_info(dumpsys: str) -> dict[str, float]:
    """Parse `dumpsys battery` output and return battery metrics."""
    result: dict[str, float] = {}
    for line in (dumpsys or "").splitlines():
        line = line.strip()
        m_level = re.match(r"level:\s*(\d+)", line)
        if m_level:
            result["battery_pct"] = float(m_level.group(1))
        m_temp = re.match(r"temperature:\s*(\d+)", line)
        if m_temp:
            result["battery_temp_c"] = int(m_temp.group(1)) / 10.0
        m_volt = re.match(r"voltage:\s*(\d+)", line)
        if m_volt:
            result["battery_voltage_v"] = int(m_volt.group(1)) / 1000.0
    return result


def _parse_meminfo(output: str) -> dict[str, float]:
    """Parse /proc/meminfo for total and available memory."""
    result: dict[str, float] = {}
    for line in (output or "").splitlines():
        m_total = re.match(r"MemTotal:\s*(\d+)\s*kB", line)
        if m_total:
            result["mem_total_mb"] = int(m_total.group(1)) / 1024.0
        m_avail = re.match(r"MemAvailable:\s*(\d+)\s*kB", line)
        if m_avail:
            result["mem_avail_mb"] = int(m_avail.group(1)) / 1024.0
    return result


def _parse_cpu_total(stat_line: str, prev_state: CpuTotalState) -> float | None:
    """Parse first line of /proc/stat for total CPU usage (%)."""
    parts = stat_line.split()
    if len(parts) < 8 or parts[0] != "cpu":
        return None
    try:
        vals = [int(x) for x in parts[1:8]]
    except Exception:
        return None
    total = sum(vals)
    idle = vals[3]
    pct: float | None = None
    if prev_state.prev_total is not None and prev_state.prev_idle is not None:
        d_total = total - prev_state.prev_total
        d_idle = idle - prev_state.prev_idle
        if d_total > 0:
            pct = max(0.0, min(100.0, (1.0 - d_idle / float(d_total)) * 100.0))
    prev_state.prev_total = total
    prev_state.prev_idle = idle
    return pct


def _parse_net_stats(output: str, target_pkg: str) -> dict[str, float]:
    """Parse /proc/net/dev for total rx/tx bytes. Returns raw bytes (caller computes rate)."""
    result: dict[str, float] = {}
    rx_total = 0
    tx_total = 0
    for line in (output or "").splitlines():
        line = line.strip()
        if ":" not in line or line.startswith("Inter") or line.startswith("face"):
            continue
        parts = line.split()
        if len(parts) < 10:
            continue
        iface = parts[0].rstrip(":")
        if iface == "lo":
            continue
        try:
            rx_total += int(parts[1])
            tx_total += int(parts[9])
        except Exception:
            pass
    if rx_total > 0 or tx_total > 0:
        result["_net_rx_bytes"] = float(rx_total)
        result["_net_tx_bytes"] = float(tx_total)
    return result


@dataclass
class NetState:
    prev_rx_bytes: float | None = None
    prev_tx_bytes: float | None = None
    prev_t_ms: int | None = None


def run_sampler_loop(
    adb: str,
    serial: str,
    stop_event: threading.Event | None = None,
    log: Callable[[str], None] | None = None,
) -> None:
    def logln(msg: str) -> None:
        line = f"{_ts()} {msg}"
        if log is not None:
            try:
                log(line)
                return
            except Exception:
                pass
        print(line)

    if not is_supported_serial(serial):
        raise RuntimeError(f"检测到无效设备 serial：{serial}")

    metrics_dir = os.path.dirname(METRICS_DEVICE_PATH)
    try:
        run_adb(adb, ["-s", serial, "shell", "mkdir", "-p", metrics_dir], timeout_s=6.0)
    except Exception:
        pass

    try:
        hz = int(run_adb(adb, ["-s", serial, "shell", "getconf", "CLK_TCK"], timeout_s=3.0).strip() or "100")
    except Exception:
        hz = 100
    try:
        cores = int(run_adb(adb, ["-s", serial, "shell", "getconf", "_NPROCESSORS_ONLN"], timeout_s=3.0).strip() or "1")
    except Exception:
        cores = 1
    cores = max(1, cores)

    cpu_state = CpuState()
    cpu_total_state = CpuTotalState()
    net_state = NetState()
    gfx_state = GfxState()
    sf_state = SfLatencyState()

    # FPS background worker — atrace streaming (1s/sample) with Perfetto fallback
    fps_bg = StreamingFpsBgWorker(adb=adb, serial=serial, logln=logln)
    fps_bg.start()

    last_fps_bg_at_ms: int = 0
    fps_bg_producing: bool = False  # True once bg worker has produced at least 2 samples

    logln(
        f"[OK] 采集服务已启动 serial={serial}（等待手机端开始监控：打开 iKunMonitor 悬浮窗/开始监控）\n"
        f"      sampler={__file__}"
    )

    last_err_at = 0.0
    last_err_msg = ""

    def log_error_rate_limited(prefix: str, err: Exception):
        nonlocal last_err_at, last_err_msg
        msg = (str(err) or "").strip()
        if not msg:
            return
        now = time.time()
        if msg != last_err_msg or (now - last_err_at) >= 5.0:
            last_err_at = now
            last_err_msg = msg
            logln(f"[ERR] {prefix}：{msg}")

    last_state_at = 0.0
    last_state_msg = ""

    def log_state_rate_limited(msg: str, min_interval_s: float = 1.5) -> None:
        nonlocal last_state_at, last_state_msg
        m = (msg or "").strip()
        if not m:
            return
        now = time.time()
        if m != last_state_msg or (now - last_state_at) >= min_interval_s:
            last_state_at = now
            last_state_msg = m
            logln(m)

    last_cfg_sig = ""
    # Keep last known good config to survive transient ADB read failures
    last_good_enabled: bool = False
    last_good_target_pkg: str = ""
    last_good_sampling_ms: int = 1000
    last_good_keys: list = []
    cfg_read_fail_streak: int = 0

    # Cache which path (external vs run-as) works for reading config / writing metrics.
    # Avoids repeated fallback attempts that each cost an ADB round-trip (~100-200ms).
    cfg_path_mode: str = ""   # "", "external", "run-as"
    metrics_path_mode: str = ""  # "", "external", "run-as"

    # Reusable thread pool (avoid creating a new pool every cycle)
    sample_pool = ThreadPoolExecutor(max_workers=7, thread_name_prefix="adb-sample")

    try:
        while True:
            if stop_event is not None and stop_event.is_set():
                logln("[OK] 采集服务已停止")
                return

            cfg: dict = {}
            cfg_text: str = ""
            cfg_source: str = "missing"

            def _read_cfg_external() -> tuple[str, str]:
                t = run_adb(adb, ["-s", serial, "shell", "cat", CONFIG_DEVICE_PATH], timeout_s=2.5).strip()
                return t, "external"

            def _read_cfg_runas() -> tuple[str, str]:
                t = run_as(adb, serial, ["cat", CONFIG_RUNAS_PATH], timeout_s=2.5).strip()
                return t, "run-as"

            # Try cached path first, then fallback to the other.
            cfg_readers = []
            if cfg_path_mode == "run-as":
                cfg_readers = [_read_cfg_runas, _read_cfg_external]
            elif cfg_path_mode == "external":
                cfg_readers = [_read_cfg_external, _read_cfg_runas]
            else:
                cfg_readers = [_read_cfg_external, _read_cfg_runas]

            for i, reader in enumerate(cfg_readers):
                try:
                    cfg_text, cfg_source = reader()
                    cfg_path_mode = cfg_source
                    break
                except Exception as e:
                    if i == len(cfg_readers) - 1:
                        # All paths failed
                        if isinstance(e, AdbError) and _is_run_as_not_debuggable(e):
                            cfg_source = "run-as:not-debuggable"
                            log_state_rate_limited(
                                "[ERR] run-as 不可用：iKunMonitor 不是 debuggable 包。\n"
                                "      解决：请用 Android Studio 以 Debug 方式安装 iKunMonitor 到该设备后重试。",
                                min_interval_s=4.0,
                            )
                        elif isinstance(e, AdbError) and _is_no_such_file(e):
                            cfg_source = "missing"
                        else:
                            if isinstance(e, AdbError):
                                log_error_rate_limited("读取配置失败", e)
                        cfg_text = ""
                    else:
                        if isinstance(e, AdbError) and not (_is_no_such_file(e) or _is_permission_denied(e)):
                            cfg_text = ""
                            break

            try:
                cfg = json.loads(cfg_text) if cfg_text else {}
            except Exception:
                cfg = {}

            enabled = bool(cfg.get("enabled"))
            target_pkg = (cfg.get("targetPackage") or "").strip()
            sampling_ms = int(cfg.get("samplingMs") or 1000)
            keys = cfg.get("metricKeys") or []

            # If config read succeeded and is valid, remember it as last good config
            if enabled and target_pkg:
                last_good_enabled = enabled
                last_good_target_pkg = target_pkg
                last_good_sampling_ms = sampling_ms
                last_good_keys = list(keys)
                cfg_read_fail_streak = 0
            elif last_good_enabled and last_good_target_pkg:
                # Config read failed or returned empty, but we had a good config before.
                # Use the last known good config to survive transient ADB failures.
                cfg_read_fail_streak += 1
                if cfg_read_fail_streak <= 10:
                    enabled = last_good_enabled
                    target_pkg = last_good_target_pkg
                    sampling_ms = last_good_sampling_ms
                    keys = last_good_keys
                else:
                    # Too many consecutive failures — monitoring may have actually stopped.
                    cfg_read_fail_streak = 0
                    last_good_enabled = False
                    last_good_target_pkg = ""

            if not enabled or not target_pkg:
                log_state_rate_limited("[WAIT] 等待手机开始监控", min_interval_s=30.0)

                fps_bg.configure("", "", [], 1000, False)

                gfx_state.prev_total = None
                gfx_state.prev_janky = None
                gfx_state.prev_t_ms = None
                gfx_state.no_frame_streak = 0
                gfx_state.fail_streak = 0
                gfx_state.disabled = False

                sf_state.layer = None
                sf_state.prev_max_present_ns = None
                sf_state.prev_t_ms = None
                sf_state.no_frame_streak = 0
                sf_state.fail_streak = 0
                sf_state.disabled = False

                cpu_total_state.prev_total = None
                cpu_total_state.prev_idle = None
                net_state.prev_rx_bytes = None
                net_state.prev_tx_bytes = None
                net_state.prev_t_ms = None

                last_fps_bg_at_ms = 0
                fps_bg_producing = False

                time.sleep(2.0)
                continue

            want = set(keys)
            cycle_start = time.monotonic()
            now_ms = int(time.time() * 1000)
            values: dict[str, float] = {}

            try:
                sig = f"{enabled}|{target_pkg}|{','.join(sorted(want)) if want else '-'}"
                if sig != last_cfg_sig:
                    last_cfg_sig = sig
                    log_state_rate_limited(
                        f"[CFG] enabled={enabled} pkg={target_pkg} keys={','.join(sorted(want)) if want else '-'}",
                        min_interval_s=0.2,
                    )
            except Exception:
                pass

            # Update FPS bg config every cycle
            layer_hint = sf_state.layer or ""
            layer_cands = sf_latency_candidates(layer_hint, target_pkg) if layer_hint else [target_pkg]
            fps_bg.configure(
                target_pkg=target_pkg,
                layer_hint=layer_hint,
                layer_candidates=layer_cands,
                sampling_ms=sampling_ms,
                enabled=True,
            )

            # ================================================================
            # Parallel ADB sampling (v13): All metrics run concurrently.
            # FPS: prioritize bg worker (atrace) when producing; only
            # fall back to gfxinfo/SF when bg worker has no data yet.
            # New: battery, mem, net, cpu_total, cpu_freq.
            # ================================================================

            want_cpu = "cpu_fg_app_pct" in want
            want_pss = "app_pss_mb" in want
            want_fps = "fps_app" in want
            want_battery = any(k.startswith("battery_") for k in want)
            want_mem = "mem_avail_mb" in want or "mem_total_mb" in want
            want_cpu_total = "cpu_total_pct" in want
            want_cpu_freq = any(k.startswith("cpu_freq_khz_") for k in want)
            want_net = "net_rx_kbps" in want or "net_tx_kbps" in want

            # Check if bg worker is reliably producing FPS
            if not fps_bg_producing and fps_bg.sample_count >= 2:
                fps_bg_producing = True

            # --- Helper: fetch PID + CPU stat in one sequential block ---
            def _sample_pid_and_cpu() -> tuple[str | None, float | None]:
                _pid: str | None = None
                _cpu_pct: float | None = None
                if not (want_cpu or want_pss):
                    return _pid, _cpu_pct
                try:
                    out = run_adb(adb, ["-s", serial, "shell", "pidof", target_pkg], timeout_s=2.5).strip()
                    _pid = out.split()[0] if out else None
                except Exception:
                    return None, None

                if _pid and want_cpu:
                    try:
                        stat = run_adb(adb, ["-s", serial, "shell", "cat", f"/proc/{_pid}/stat"], timeout_s=2.5)
                        ticks = _parse_proc_stat_ticks(stat.strip().splitlines()[0])
                        if ticks is not None:
                            if cpu_state.prev_ticks is not None and cpu_state.prev_t_ms is not None:
                                dt_ms = max(1, now_ms - cpu_state.prev_t_ms)
                                dt_ticks = max(0, ticks - cpu_state.prev_ticks)
                                cpu_sec = dt_ticks / float(max(1, hz))
                                wall_sec = dt_ms / 1000.0
                                pct = cpu_sec / (wall_sec * float(cores)) * 100.0
                                _cpu_pct = max(0.0, min(100.0, pct))
                            cpu_state.prev_ticks = ticks
                            cpu_state.prev_t_ms = now_ms
                    except Exception:
                        pass
                return _pid, _cpu_pct

            # --- Helper: fetch PSS memory ---
            def _sample_pss() -> float | None:
                if not want_pss:
                    return None
                try:
                    out = run_adb(adb, ["-s", serial, "shell", "dumpsys", "meminfo", target_pkg], timeout_s=3.0)
                    pss_kb = _parse_pss_kb(out)
                    if pss_kb is not None and pss_kb > 0:
                        return pss_kb / 1024.0
                except Exception:
                    pass
                return None

            # --- Helper: FPS — bg worker first, fallback only when bg not producing ---
            def _sample_fps() -> float | None:
                if not want_fps:
                    return None

                # Strategy: If bg worker is producing, just read from it (zero ADB cost).
                # Only do the expensive fallback chain if bg worker hasn't started producing yet.
                if fps_bg_producing:
                    pfps, pfps_at, _pdetail = fps_bg.read_latest()
                    if pfps is not None and pfps >= 0 and pfps_at > last_fps_bg_at_ms:
                        return max(0.0, pfps)
                    # bg worker may be between samples; wait briefly
                    for _w in range(6):
                        time.sleep(0.05)
                        pfps, pfps_at, _pdetail = fps_bg.read_latest()
                        if pfps is not None and pfps >= 0 and pfps_at > last_fps_bg_at_ms:
                            return max(0.0, pfps)
                    # bg worker temporarily stalled; still return last known value
                    if pfps is not None and pfps >= 0:
                        return max(0.0, pfps)
                    return None

                # bg worker not yet producing — use fallback chain (only first few seconds)
                _fps_val: float | None = None

                # 1. gfxinfo (skip if disabled)
                if not gfx_state.disabled:
                    try:
                        out = run_adb(adb, ["-s", serial, "shell", "dumpsys", "gfxinfo", target_pkg, "framestats"], timeout_s=3.0)
                        total, janky = _parse_gfx_totals(out)
                        if total is None:
                            gfx_state.fail_streak += 1
                        else:
                            if gfx_state.prev_total is not None and gfx_state.prev_t_ms is not None:
                                dt_ms = max(1, now_ms - gfx_state.prev_t_ms)
                                d_total = max(0, total - gfx_state.prev_total)
                                if d_total > 0:
                                    gfx_state.no_frame_streak = 0
                                    gfx_state.fail_streak = 0
                                    _fps_val = max(0.0, d_total * 1000.0 / dt_ms)
                                else:
                                    gfx_state.no_frame_streak += 1
                                    gfx_state.fail_streak += 1
                            gfx_state.prev_total = total
                            gfx_state.prev_janky = janky
                            gfx_state.prev_t_ms = now_ms
                    except Exception:
                        gfx_state.fail_streak += 1

                    if gfx_state.fail_streak >= 3 and not gfx_state.disabled:
                        gfx_state.disabled = True
                        logln("[INFO] gfxinfo 连续失败，已禁用（UE4/Vulkan 不走 HW renderer，属正常现象）")

                if _fps_val is not None:
                    return _fps_val

                # 2. Check bg worker (it may have started producing during our fallback)
                pfps, pfps_at, _ = fps_bg.read_latest()
                if pfps is not None and pfps >= 0 and pfps_at > last_fps_bg_at_ms:
                    return max(0.0, pfps)

                # 3. Wait for bg worker (shorter wait since it's the first few cycles)
                for _w in range(10):
                    time.sleep(0.05)
                    pfps, pfps_at, _ = fps_bg.read_latest()
                    if pfps is not None and pfps >= 0 and pfps_at > last_fps_bg_at_ms:
                        return max(0.0, pfps)

                return None

            # --- Helper: battery info ---
            def _sample_battery() -> dict[str, float]:
                if not want_battery:
                    return {}
                try:
                    out = run_adb(adb, ["-s", serial, "shell", "dumpsys", "battery"], timeout_s=3.0)
                    return _parse_battery_info(out)
                except Exception:
                    return {}

            # --- Helper: system memory ---
            def _sample_mem() -> dict[str, float]:
                if not want_mem:
                    return {}
                try:
                    out = run_adb(adb, ["-s", serial, "shell", "cat", "/proc/meminfo"], timeout_s=2.5)
                    return _parse_meminfo(out)
                except Exception:
                    return {}

            # --- Helper: total CPU + per-core freq ---
            def _sample_cpu_sys() -> dict[str, float]:
                result: dict[str, float] = {}
                if not (want_cpu_total or want_cpu_freq):
                    return result
                if want_cpu_total:
                    try:
                        out = run_adb(adb, ["-s", serial, "shell", "head", "-1", "/proc/stat"], timeout_s=2.5)
                        stat_line = (out or "").strip().splitlines()[0] if (out or "").strip() else ""
                        pct = _parse_cpu_total(stat_line, cpu_total_state)
                        if pct is not None:
                            result["cpu_total_pct"] = pct
                    except Exception:
                        pass
                if want_cpu_freq:
                    try:
                        # Use cat with glob directly — avoids $() substitution issues with sh_double_quote
                        out = run_adb(
                            adb,
                            ["-s", serial, "shell",
                             "cat", "/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq"],
                            timeout_s=2.5,
                        )
                        # Each line is one core's frequency in kHz, in cpu number order
                        idx = 0
                        for line in (out or "").splitlines():
                            line = line.strip()
                            if not line:
                                continue
                            try:
                                val = int(line)
                                if val > 0:
                                    result[f"cpu_freq_khz_{idx}"] = float(val)
                                    idx += 1
                            except Exception:
                                pass
                    except Exception:
                        pass
                return result

            # --- Helper: network stats ---
            def _sample_net() -> dict[str, float]:
                if not want_net:
                    return {}
                try:
                    out = run_adb(adb, ["-s", serial, "shell", "cat", "/proc/net/dev"], timeout_s=2.5)
                    raw = _parse_net_stats(out, target_pkg)
                    result: dict[str, float] = {}
                    rx_bytes = raw.get("_net_rx_bytes")
                    tx_bytes = raw.get("_net_tx_bytes")
                    if rx_bytes is not None and tx_bytes is not None:
                        if net_state.prev_rx_bytes is not None and net_state.prev_t_ms is not None:
                            dt_ms = max(1, now_ms - net_state.prev_t_ms)
                            dt_s = dt_ms / 1000.0
                            d_rx = max(0.0, rx_bytes - net_state.prev_rx_bytes)
                            d_tx = max(0.0, tx_bytes - net_state.prev_tx_bytes)
                            result["net_rx_kbps"] = (d_rx / 1024.0) / dt_s * 8.0
                            result["net_tx_kbps"] = (d_tx / 1024.0) / dt_s * 8.0
                        net_state.prev_rx_bytes = rx_bytes
                        net_state.prev_tx_bytes = tx_bytes
                        net_state.prev_t_ms = now_ms
                    return result
                except Exception:
                    return {}

            # --- Execute all sampling tasks in parallel (reuse pool) ---
            fut_cpu: Future = sample_pool.submit(_sample_pid_and_cpu)
            fut_pss: Future = sample_pool.submit(_sample_pss)
            fut_fps: Future = sample_pool.submit(_sample_fps)
            fut_battery: Future = sample_pool.submit(_sample_battery)
            fut_mem: Future = sample_pool.submit(_sample_mem)
            fut_cpu_sys: Future = sample_pool.submit(_sample_cpu_sys)
            fut_net: Future = sample_pool.submit(_sample_net)

            # Use short timeouts — don't let one slow ADB call block the whole cycle
            try:
                pid_result, cpu_pct = fut_cpu.result(timeout=4.0)
            except Exception:
                pid_result, cpu_pct = None, None
            try:
                pss_mb = fut_pss.result(timeout=4.0)
            except Exception:
                pss_mb = None
            try:
                fps_val = fut_fps.result(timeout=4.0)
            except Exception:
                fps_val = None
            try:
                battery_vals = fut_battery.result(timeout=4.0)
            except Exception:
                battery_vals = {}
            try:
                mem_vals = fut_mem.result(timeout=4.0)
            except Exception:
                mem_vals = {}
            try:
                cpu_sys_vals = fut_cpu_sys.result(timeout=4.0)
            except Exception:
                cpu_sys_vals = {}
            try:
                net_vals = fut_net.result(timeout=4.0)
            except Exception:
                net_vals = {}

            if cpu_pct is not None:
                values["cpu_fg_app_pct"] = cpu_pct
            if pss_mb is not None:
                values["app_pss_mb"] = pss_mb
            if fps_val is not None:
                values["fps_app"] = fps_val
                if fps_val >= 0:
                    pfps, pfps_at, _ = fps_bg.read_latest()
                    if pfps is not None and pfps == fps_val and pfps_at > last_fps_bg_at_ms:
                        last_fps_bg_at_ms = pfps_at
            values.update(battery_vals)
            values.update(mem_vals)
            values.update(cpu_sys_vals)
            values.update(net_vals)

            got_keys = sorted(values.keys())

            log_state_rate_limited(
                f"[SAMPLE] pkg={target_pkg} want={len(want)} got={len(got_keys)} gotKeys={','.join(got_keys) if got_keys else '-'} rev={SAMPLER_REV} wantFps={1 if want_fps else 0} fpsSamples={fps_bg.sample_count}",
                min_interval_s=1.2,
            )

            payload = {"pkg": target_pkg, "t": now_ms, "v": values}
            payload_text = json.dumps(payload, ensure_ascii=False)

            wrote = False

            def _write_external(text: str) -> None:
                cmd = f"cat > '{METRICS_DEVICE_PATH}'"
                run_adb(
                    adb,
                    ["-s", serial, "shell", "sh", "-c", sh_double_quote(cmd)],
                    timeout_s=3.0,
                    input_text=text,
                )

            def _write_runas(text: str) -> None:
                cmd2 = f"cat > '{METRICS_RUNAS_PATH}'"
                run_as(
                    adb,
                    serial,
                    ["sh", "-c", sh_double_quote(cmd2)],
                    timeout_s=3.0,
                    input_text=text,
                )

            # Try cached path first, then fallback.
            writers = []
            if metrics_path_mode == "run-as":
                writers = [("run-as", _write_runas), ("external", _write_external)]
            elif metrics_path_mode == "external":
                writers = [("external", _write_external), ("run-as", _write_runas)]
            else:
                writers = [("external", _write_external), ("run-as", _write_runas)]

            for i, (wmode, writer) in enumerate(writers):
                try:
                    writer(payload_text)
                    wrote = True
                    metrics_path_mode = wmode
                    break
                except Exception as e:
                    if i == len(writers) - 1:
                        if isinstance(e, AdbError) and _is_run_as_not_debuggable(e):
                            log_state_rate_limited(
                                "[ERR] 写回 metrics 失败：run-as 不可用（iKunMonitor 不是 debuggable 包）。",
                                min_interval_s=4.0,
                            )
                        elif isinstance(e, AdbError):
                            log_error_rate_limited("写入采集结果失败", e)
                    else:
                        if isinstance(e, AdbError) and not (_is_no_such_file(e) or _is_permission_denied(e)):
                            if isinstance(e, AdbError):
                                log_error_rate_limited("写入采集结果失败", e)
                            break

            if not wrote:
                log_state_rate_limited("[ERR] 写回 metrics 失败（无可用写入路径）", min_interval_s=3.0)

            # Smart sleep: subtract time already spent on sampling this cycle.
            elapsed_s = time.monotonic() - cycle_start
            remain_s = max(0.05, sampling_ms / 1000.0 - elapsed_s)
            time.sleep(remain_s)

            if not wrote:
                continue
    finally:
        fps_bg.stop()
        sample_pool.shutdown(wait=False)


def main():
    adb = resolve_adb_path()

    serial_env = os.environ.get("PM_SERIAL", "").strip()
    serial = serial_env or pick_default_serial(adb)

    if not is_supported_serial(serial):
        raise RuntimeError("检测到无效设备 serial：{}".format(serial))

    print(f"Using adb={adb}")
    print(f"Using serial={serial}")
    run_sampler_loop(adb=adb, serial=serial)


if __name__ == "__main__":
    main()
