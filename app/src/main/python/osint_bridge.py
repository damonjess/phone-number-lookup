import asyncio
import hashlib
import json
import random
import string
import sys
import types
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

# ---------------------------------------------------------------------------
# 1. Stand-in shims for desktop-only dependencies (pandas, tomli, selenium)
# ---------------------------------------------------------------------------
def _install_android_shims():
    # Python 3.11+ includes tomllib in the standard library; alias it as tomli
    if "tomli" not in sys.modules:
        try:
            import tomllib
            sys.modules["tomli"] = tomllib
        except ImportError:
            pass

    # Sherlock imports pandas at top-level only for optional .xlsx export
    if "pandas" not in sys.modules:
        pd_shim = types.ModuleType("pandas")
        pd_shim.DataFrame = lambda *args, **kwargs: None
        sys.modules["pandas"] = pd_shim

    if "openpyxl" not in sys.modules:
        sys.modules["openpyxl"] = types.ModuleType("openpyxl")

    # Tookie imports selenium & webdriver_manager for optional browser mode
    for mod_name in [
        "selenium",
        "selenium.webdriver",
        "selenium.webdriver.common",
        "selenium.webdriver.common.by",
        "selenium.webdriver.chrome",
        "selenium.webdriver.chrome.service",
        "selenium.webdriver.chrome.options",
        "webdriver_manager",
        "webdriver_manager.chrome",
    ]:
        if mod_name not in sys.modules:
            m = types.ModuleType(mod_name)
            m.By = object
            m.Service = object
            m.Options = object
            m.ChromeDriverManager = object
            sys.modules[mod_name] = m


_install_android_shims()

import requests
import aiohttp

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Mobile Safari/537.36"
)

SHERLOCK_DATA_URL = "https://raw.githubusercontent.com/sherlock-project/sherlock/master/sherlock_project/resources/data.json"
TOOKIE_DATA_URL = "https://raw.githubusercontent.com/Alfredredbird/tookie-osint/main/sites/sites.json"
BLACKBIRD_WMN_URL = "https://raw.githubusercontent.com/p1ngul1n0/blackbird/main/data/wmn-data.json"
BLACKBIRD_EMAIL_URL = "https://raw.githubusercontent.com/p1ngul1n0/blackbird/main/data/email-data.json"

_CACHE = {}


def _get_json(url: str):
    if url in _CACHE:
        return _CACHE[url]
    resp = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=20)
    resp.raise_for_status()
    data = resp.json()
    _CACHE[url] = data
    return data


def _random_handle(length: int = 14) -> str:
    chars = string.ascii_lowercase + string.digits
    return random.choice(string.ascii_lowercase) + "".join(random.choices(chars, k=length - 1))


# ---------------------------------------------------------------------------
# 2. SHERLOCK PYTHON ENGINE
# ---------------------------------------------------------------------------
def run_sherlock_python(username: str, max_sites: int = 150) -> str:
    """
    Uses cloned sherlock_project package if present in app/src/main/python/sherlock_project,
    otherwise executes Sherlock's exact status_code / message / response_url probe logic in Python.
    """
    username = username.strip().lstrip("@")
    hits = []
    issues = []
    discarded = []

    # Try local cloned sherlock_project first
    try:
        from sherlock_project.sherlock import sherlock
        from sherlock_project.sites import SitesInformation
        from sherlock_project.notify import QueryNotify
        from sherlock_project.result import QueryStatus

        class SilentNotify(QueryNotify):
            def __init__(self):
                super().__init__()
            def start(self, message=None):
                pass
            def update(self, result):
                pass
            def finish(self, message=None):
                pass

        sites = SitesInformation()
        site_data_all = {site.name: site.information for site in sites}
        if max_sites and max_sites > 0:
            keys = list(site_data_all.keys())[:max_sites]
            site_data = {k: site_data_all[k] for k in keys}
        else:
            site_data = site_data_all

        raw_results = sherlock(
            username=username,
            site_data=site_data,
            query_notify=SilentNotify(),
            timeout=10,
        )
        for site_name, info in raw_results.items():
            status_obj = info.get("status")
            status_val = getattr(status_obj, "status", None)
            url_user = info.get("url_user", "")
            http_status = int(info.get("http_status") or 200)
            if status_val == QueryStatus.CLAIMED:
                hits.append({
                    "site": site_name,
                    "category": "sherlock",
                    "source": "Python: Sherlock",
                    "profileUrl": url_user,
                    "statusCode": http_status,
                    "evidence": "Sherlock QueryStatus.CLAIMED (Python engine)"
                })
        return json.dumps({
            "sitesChecked": len(site_data),
            "hits": hits,
            "discarded": discarded,
            "issues": issues
        })
    except Exception:
        pass

    # Built-in Python Sherlock runner against Sherlock's upstream data.json
    try:
        data = _get_json(SHERLOCK_DATA_URL)
    except Exception as e:
        return json.dumps({"sitesChecked": 0, "hits": [], "discarded": [], "issues": [], "error": str(e)})

    entries = [(k, v) for k, v in data.items() if not k.startswith("$") and isinstance(v, dict)]
    if max_sites and max_sites > 0:
        entries = entries[:max_sites]

    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})

    def check_one(site_name, cfg, acct):
        url_template = cfg.get("urlProbe") or cfg.get("url") or ""
        profile_url = (cfg.get("url") or url_template).replace("{}", acct)
        if "{}" not in url_template:
            return None
        probe_url = url_template.replace("{}", urllib.parse.quote(acct))
        error_type = cfg.get("errorType", "status_code")
        method = cfg.get("request_method", "GET").upper()
        headers = dict(cfg.get("headers") or {})
        payload = cfg.get("request_payload")

        try:
            if method == "POST":
                body_str = json.dumps(payload).replace("{}", acct) if isinstance(payload, dict) else str(payload or "").replace("{}", acct)
                r = session.post(probe_url, data=body_str, headers=headers, timeout=10, allow_redirects=True)
            else:
                r = session.get(probe_url, headers=headers, timeout=10, allow_redirects=True)
        except Exception as ex:
            return ("ISSUE", site_name, profile_url, 0, str(ex))

        err_codes = cfg.get("errorCode")
        err_code_set = set(err_codes) if isinstance(err_codes, list) else ({err_codes} if isinstance(err_codes, int) else {404})
        err_msgs = cfg.get("errorMsg")
        err_msg_list = err_msgs if isinstance(err_msgs, list) else ([err_msgs] if isinstance(err_msgs, str) else [])
        err_url = cfg.get("errorUrl") or ""

        if r.status_code in (404, 410) or r.status_code in err_code_set:
            return ("NOT_FOUND", site_name, profile_url, r.status_code, "")

        text = r.text[:250000]
        if error_type == "message":
            for msg in err_msg_list:
                if msg and msg.lower() in text.lower():
                    return ("NOT_FOUND", site_name, profile_url, r.status_code, "")
            if 200 <= r.status_code < 300:
                return ("FOUND", site_name, profile_url, r.status_code, "Sherlock message check passed (Python)")
        elif error_type == "response_url":
            if err_url and err_url.lower() in r.url.lower():
                return ("NOT_FOUND", site_name, profile_url, r.status_code, "")
            if 200 <= r.status_code < 300:
                return ("FOUND", site_name, profile_url, r.status_code, "Sherlock response_url check passed (Python)")
        else:
            if 200 <= r.status_code < 300:
                return ("FOUND", site_name, profile_url, r.status_code, f"Sherlock status_code {r.status_code} (Python)")

        return ("ISSUE", site_name, profile_url, r.status_code, f"HTTP {r.status_code}")

    candidate_hits = []
    with ThreadPoolExecutor(max_workers=10) as pool:
        future_map = {pool.submit(check_one, name, cfg, username): (name, cfg) for name, cfg in entries}
        for fut in as_completed(future_map):
            name, cfg = future_map[fut]
            res = fut.result()
            if not res:
                continue
            verdict, sname, purl, code, ev = res
            if verdict == "FOUND":
                candidate_hits.append((sname, cfg, purl, code, ev))
            elif verdict == "ISSUE":
                issues.append({"site": sname, "profileUrl": purl, "code": code, "detail": ev})

        # Control check with random username to filter soft-404s
        ctrl_name = _random_handle()
        ctrl_futures = {pool.submit(check_one, sname, cfg, ctrl_name): (sname, cfg, purl, code, ev)
                        for sname, cfg, purl, code, ev in candidate_hits}
        for fut in as_completed(ctrl_futures):
            sname, cfg, purl, code, ev = ctrl_futures[fut]
            ctrl_res = fut.result()
            if ctrl_res and ctrl_res[0] == "FOUND":
                discarded.append(sname)
            else:
                tags = cfg.get("tags")
                cat = tags[0] if isinstance(tags, list) and tags else ("nsfw" if cfg.get("isNSFW") else "general")
                hits.append({
                    "site": sname,
                    "category": str(cat),
                    "source": "Python: Sherlock",
                    "profileUrl": purl,
                    "statusCode": code,
                    "evidence": ev + " • control verified"
                })

    return json.dumps({
        "sitesChecked": len(entries),
        "hits": hits,
        "discarded": discarded,
        "issues": issues
    })


# ---------------------------------------------------------------------------
# 3. TOOKIE-OSINT V4 PYTHON LIBRARY ENGINE (scan_site + is_found + control)
# ---------------------------------------------------------------------------
def tookie_is_found(response, error_message: str) -> bool:
    if response is None:
        return False
    if response.status_code < 200 or response.status_code >= 300:
        return False
    if error_message and error_message.lower() != "none":
        if error_message.lower() in response.text[:250000].lower():
            return False
    return True


def tookie_scan_site(session: requests.Session, site_entry: dict, username: str):
    base = site_entry.get("site", "")
    if not base:
        return None
    error_msg = str(site_entry.get("errorMessage") or "")
    nsfw = str(site_entry.get("nsfw", "false")).lower() == "true"
    url = base + urllib.parse.quote(username)
    site_label = base.replace("https://", "").replace("http://", "").replace("www.", "").split("/")[0]

    try:
        resp = session.get(url, timeout=10, allow_redirects=True)
    except Exception as ex:
        return ("ISSUE", site_label, url, 0, str(ex), nsfw)

    if tookie_is_found(resp, error_msg):
        return ("FOUND", site_label, url, resp.status_code, "Tookie is_found() matched in Python", nsfw)
    elif resp.status_code in (404, 410) or (error_msg and error_msg.lower() in resp.text[:250000].lower()):
        return ("NOT_FOUND", site_label, url, resp.status_code, "", nsfw)
    else:
        return ("ISSUE", site_label, url, resp.status_code, f"HTTP {resp.status_code}", nsfw)


def run_tookie_python(username: str, max_sites: int = 150) -> str:
    username = username.strip().lstrip("@")
    try:
        sites_list = _get_json(TOOKIE_DATA_URL)
    except Exception as e:
        return json.dumps({"sitesChecked": 0, "hits": [], "discarded": [], "issues": [], "error": str(e)})

    if max_sites and max_sites > 0:
        sites_list = sites_list[:max_sites]

    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})

    hits = []
    discarded = []
    issues = []
    candidates = []

    with ThreadPoolExecutor(max_workers=10) as pool:
        futs = {pool.submit(tookie_scan_site, session, entry, username): entry for entry in sites_list}
        for fut in as_completed(futs):
            entry = futs[fut]
            res = fut.result()
            if not res:
                continue
            verdict, label, purl, code, ev, nsfw = res
            if verdict == "FOUND":
                candidates.append((entry, label, purl, code, ev, nsfw))
            elif verdict == "ISSUE":
                issues.append({"site": label, "profileUrl": purl, "code": code, "detail": ev})

        # Tookie V4 control-response verification
        ctrl_user = _random_handle()
        ctrl_futs = {
            pool.submit(tookie_scan_site, session, entry, ctrl_user): (label, purl, code, ev, nsfw)
            for entry, label, purl, code, ev, nsfw in candidates
        }
        for fut in as_completed(ctrl_futs):
            label, purl, code, ev, nsfw = ctrl_futs[fut]
            ctrl_res = fut.result()
            if ctrl_res and ctrl_res[0] == "FOUND":
                discarded.append(label)
            else:
                hits.append({
                    "site": label,
                    "category": "nsfw" if nsfw else "social",
                    "source": "Python: Tookie-OSINT",
                    "profileUrl": purl,
                    "statusCode": code,
                    "evidence": ev + " • control verified"
                })

    return json.dumps({
        "sitesChecked": len(sites_list),
        "hits": hits,
        "discarded": discarded,
        "issues": issues
    })


# ---------------------------------------------------------------------------
# 4. BLACKBIRD PYTHON ENGINE (aiohttp async username + email scanner)
# ---------------------------------------------------------------------------
def _transform_blackbird_input(account: str, op: str) -> str:
    clean = account.strip().lower().encode("utf-8")
    if op == "hash-md5":
        return hashlib.md5(clean).hexdigest()
    if op == "hash-sha256":
        return hashlib.sha256(clean).hexdigest()
    return account.strip()


async def _blackbird_probe_one(session: aiohttp.ClientSession, sem: asyncio.Semaphore, site: dict, account: str, is_email: bool):
    name = site.get("name", "Unknown")
    cat = site.get("cat", "general")
    uri_check = site.get("uri_check", "")
    uri_pretty = site.get("uri_pretty") or uri_check
    method = (site.get("method") or ("POST" if site.get("post_body") else "GET")).upper()
    body_tmpl = site.get("data") or site.get("post_body")
    op = site.get("input_operation", "")

    if "{account}" not in uri_check and (not body_tmpl or "{account}" not in str(body_tmpl)):
        return None

    target = _transform_blackbird_input(account, op)
    url = uri_check.replace("{account}", urllib.parse.quote(target))
    profile_url = (uri_pretty.replace("{account}", account) if not is_email else uri_check.replace("{account}", "").rstrip("/"))
    headers = {"User-Agent": USER_AGENT}
    if isinstance(site.get("headers"), dict):
        headers.update({k: str(v) for k, v in site["headers"].items() if v is not None})

    data_bytes = None
    if body_tmpl and method == "POST":
        data_bytes = str(body_tmpl).replace("{account}", target).encode("utf-8")

    e_code = int(site.get("e_code", 200))
    m_code = int(site.get("m_code", 404))
    e_string = str(site.get("e_string") or "").replace("{account}", account)
    m_string = str(site.get("m_string") or "").replace("{account}", account)

    async with sem:
        try:
            async with session.request(method, url, headers=headers, data=data_bytes, timeout=aiohttp.ClientTimeout(total=10)) as resp:
                status = resp.status
                text = await resp.text(errors="ignore")
                text = text[:250000]
        except Exception as ex:
            return ("ISSUE", name, cat, profile_url, 0, str(ex))

    if status == m_code or (m_string and m_string.lower() in text.lower()):
        return ("NOT_FOUND", name, cat, profile_url, status, "")

    code_ok = (status == e_code)
    str_ok = (not e_string) or (e_string.lower() in text.lower())
    if code_ok and str_ok:
        return ("FOUND", name, cat, profile_url, status, "Blackbird aiohttp marker matched (Python)")

    return ("ISSUE", name, cat, profile_url, status, f"HTTP {status}")


async def _run_blackbird_async(target: str, is_email: bool, max_sites: int):
    url = BLACKBIRD_EMAIL_URL if is_email else BLACKBIRD_WMN_URL
    data = _get_json(url)
    sites = [s for s in (data.get("sites") or []) if s.get("valid", True)]
    if not is_email and max_sites and max_sites > 0:
        sites = sites[:max_sites]

    sem = asyncio.Semaphore(12)
    connector = aiohttp.TCPConnector(ssl=False, limit=20)
    hits = []
    discarded = []
    issues = []

    async with aiohttp.ClientSession(connector=connector) as session:
        tasks = [_blackbird_probe_one(session, sem, s, target, is_email) for s in sites]
        results = await asyncio.gather(*tasks)

        candidates = []
        for s, res in zip(sites, results):
            if not res:
                continue
            verdict, name, cat, purl, status, ev = res
            if verdict == "FOUND":
                candidates.append((s, name, cat, purl, status, ev))
            elif verdict == "ISSUE":
                issues.append({"site": name, "profileUrl": purl, "code": status, "detail": ev})

        # Control check for false-positive elimination
        if is_email and "@" in target:
            ctrl_target = _random_handle(11) + "@" + target.split("@", 1)[1]
        else:
            ctrl_target = _random_handle(14)

        ctrl_tasks = [_blackbird_probe_one(session, sem, s, ctrl_target, is_email) for s, *_ in candidates]
        ctrl_results = await asyncio.gather(*ctrl_tasks)

        for (s, name, cat, purl, status, ev), ctrl_res in zip(candidates, ctrl_results):
            if ctrl_res and ctrl_res[0] == "FOUND":
                discarded.append(name)
            else:
                hits.append({
                    "site": name,
                    "category": cat,
                    "source": "Python: Blackbird",
                    "profileUrl": purl,
                    "statusCode": status,
                    "evidence": ev + " • control verified"
                })

    return {
        "sitesChecked": len(sites),
        "hits": hits,
        "discarded": discarded,
        "issues": issues
    }


def run_blackbird_python(target: str, is_email: bool = False, max_sites: int = 150) -> str:
    target = target.strip()
    if not is_email:
        target = target.lstrip("@")
    try:
        out = asyncio.run(_run_blackbird_async(target, is_email, max_sites))
        return json.dumps(out)
    except Exception as e:
        return json.dumps({"sitesChecked": 0, "hits": [], "discarded": [], "issues": [], "error": str(e)})
