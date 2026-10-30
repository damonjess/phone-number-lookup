"""Mobile adapters for the upstream Sherlock, Blackbird, and Tookie scan cores.

The upstream command-line interfaces are not launched. Their own Python scan
functions are called in-process and their results are normalized for Android.
"""
import contextlib
import io
import json
import os
import random
import sys
import tempfile
import traceback
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(ROOT, "vendor")
SHERLOCK_DIR = os.path.join(VENDOR, "sherlock_project")
BLACKBIRD_DIR = os.path.join(VENDOR, "blackbird")
TOOKIE_DIR = os.path.join(VENDOR, "tookie_osint")
BLACKBIRD_EMAIL_URL = "https://raw.githubusercontent.com/p1ngul1n0/blackbird/main/data/email-data.json"
MAX_EMAIL_DATASET_BYTES = 8 * 1024 * 1024


def _json_result(sites_checked=0, hits=None, issues=None, error=None):
    return {
        "sitesChecked": int(sites_checked or 0),
        "hits": hits or [],
        "discarded": [],
        "issues": issues or [],
        **({"error": str(error)} if error else {}),
    }


def _clear_cli_modules():
    # Sherlock is separately namespaced. Blackbird and Tookie both use the
    # generic top-level package name `modules`, so they must be invoked
    # sequentially and imported from only one vendor tree at a time.
    for name in list(sys.modules):
        if name == "modules" or name.startswith("modules.") or name == "config":
            del sys.modules[name]
    sys.path[:] = [p for p in sys.path if p not in (BLACKBIRD_DIR, TOOKIE_DIR)]


def _prepare_email_dataset():
    """Use the current upstream site list when reachable; never lose the bundled fallback."""
    bundled_path = os.path.join(BLACKBIRD_DIR, "data", "email-data.json")
    try:
        request = urllib.request.Request(
            BLACKBIRD_EMAIL_URL,
            headers={"User-Agent": "PhoneNumberLookup/1.0", "Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=8) as response:
            raw = response.read(MAX_EMAIL_DATASET_BYTES + 1)
        if len(raw) > MAX_EMAIL_DATASET_BYTES:
            return bundled_path
        dataset = json.loads(raw.decode("utf-8"))
        sites = dataset.get("sites") if isinstance(dataset, dict) else None
        if not isinstance(sites, list) or not sites:
            return bundled_path
        if any(not isinstance(site, dict) or not site.get("name") or not site.get("uri_check") for site in sites):
            return bundled_path
        fd, path = tempfile.mkstemp(prefix="blackbird-email-", suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(dataset, output, ensure_ascii=False)
        return path
    except Exception:
        return bundled_path


def run_sherlock_python(username, max_sites=150):
    username = str(username).strip().lstrip("@")
    if not username:
        return json.dumps(_json_result(error="Enter a username"))
    try:
        if VENDOR not in sys.path:
            sys.path.insert(0, VENDOR)
        from sherlock_project.sherlock import sherlock
        from sherlock_project.sites import SitesInformation
        from sherlock_project.notify import QueryNotify
        from sherlock_project.result import QueryStatus

        class SilentNotify(QueryNotify):
            def start(self, message=None):
                pass
            def update(self, result):
                pass
            def finish(self, message=None):
                pass

        sites_obj = SitesInformation(data_file_path=os.path.join(SHERLOCK_DIR, "resources", "data.json"))
        sites = {site.name: site.information for site in sites_obj}
        if max_sites and int(max_sites) > 0:
            sites = dict(list(sites.items())[:int(max_sites)])
        raw = sherlock(username=username, site_data=sites, query_notify=SilentNotify(), timeout=15)
        hits, issues = [], []
        for name, info in raw.items():
            status = info.get("status")
            code = info.get("http_status") or 0
            url = info.get("url_user") or ""
            if getattr(status, "status", None) == QueryStatus.CLAIMED:
                hits.append({"site": name, "category": "social", "source": "Python: Sherlock upstream",
                             "profileUrl": url, "statusCode": int(code or 200),
                             "evidence": "Sherlock upstream reported a claimed username"})
            elif code and int(code) not in (404, 410):
                issues.append({"site": name, "profileUrl": url, "code": int(code),
                               "detail": "Upstream Sherlock could not confirm this site"})
        return json.dumps(_json_result(len(sites), hits, issues))
    except Exception as exc:
        return json.dumps(_json_result(error="Sherlock upstream failed: " + str(exc)))


def _blackbird_config(is_email, target, max_sites):
    import config
    from rich.console import Console

    data_dir = os.path.join(BLACKBIRD_DIR, "data")
    config.USERNAME_LIST_PATH = os.path.join(data_dir, "wmn-data.json")
    config.EMAIL_LIST_PATH = os.path.join(data_dir, "email-data.json")
    config.USERNAME_METADATA_LIST_PATH = os.path.join(data_dir, "wmn-metadata.json")
    config.USERNAME_LIST_URL = "https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json"
    config.USERNAME_LIST_FILENAME = "wmn-data.json"
    config.USERNAME_METADATA_LIST_FILENAME = "wmn-metadata.json"
    config.EMAIL_LIST_FILENAME = "email-data.json"
    config.EMAIL_LIST_PATH = _prepare_email_dataset() if is_email else os.path.join(data_dir, "email-data.json")
    config.LOG_PATH = os.path.join(tempfile.gettempdir(), "blackbird.log")
    config.console = Console(file=io.StringIO(), force_terminal=False, no_color=True)
    config.max_concurrent_requests = 16
    config.timeout = 15
    config.proxy = None
    config.verbose = False
    config.filter = None
    config.no_nsfw = True
    config.dump = False
    config.ai = False
    config.aiModel = None
    config.currentUser = target if not is_email else None
    config.currentEmail = target if is_email else None
    config.userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36"
    config.instagram_session_id = None
    config.api_url = None
    config.dateRaw = "mobile"
    config.datePretty = "Mobile app"
    config.saveDirectory = tempfile.gettempdir()
    config.metadata_params = {"sites": {}}
    config._mobile_dataset_path = None
    config._mobile_email_dataset_path = config.EMAIL_LIST_PATH if is_email and config.EMAIL_LIST_PATH != os.path.join(data_dir, "email-data.json") else None

    if not is_email and max_sites and int(max_sites) > 0:
        with open(config.USERNAME_LIST_PATH, "r", encoding="utf-8") as f:
            dataset = json.load(f)
        dataset["sites"] = dataset.get("sites", [])[:int(max_sites)]
        fd, data_path = tempfile.mkstemp(prefix="wmn-mobile-", suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(dataset, f)
        config.USERNAME_LIST_PATH = data_path
        config._mobile_dataset_path = data_path
    return config


def _clean_profile_url(site_name, raw_url):
    name_lower = str(site_name).lower()
    url_lower = str(raw_url).lower()
    if "twitter" in name_lower or name_lower == "x" or "twitter.com" in url_lower or "x.com" in url_lower:
        if any(k in url_lower for k in ["email_available", "/i/users/", "api.twitter.com", "api.x.com", ".json"]):
            return "https://x.com/"
        cleaned = str(raw_url).replace("{account}", "").rstrip('/')
        if cleaned in ("https://twitter.com", "https://x.com", "https://twitter.com/i", "https://x.com/i") or "/i/users" in cleaned:
            return "https://x.com/"
        return str(raw_url).replace("twitter.com", "x.com")
    if "spotify" in name_lower or "spotify.com" in url_lower:
        return "https://open.spotify.com/"
    if "firefox" in name_lower or "firefox.com" in url_lower:
        return "https://accounts.firefox.com/"
    if "chess.com" in name_lower or "chess.com" in url_lower:
        return "https://www.chess.com/"
    if "github" in name_lower or "github.com" in url_lower:
        return "https://github.com/"
    if "duolingo" in name_lower or "duolingo.com" in url_lower:
        return "https://www.duolingo.com/"
    if "gravatar" in name_lower or "gravatar.com" in url_lower:
        return "https://gravatar.com/"
    if "wordpress" in name_lower or "wordpress.com" in url_lower:
        return "https://wordpress.com/"
    if "keybase" in name_lower or "keybase.io" in url_lower:
        return "https://keybase.io/"
    if "mastodon" in name_lower or "mastodon" in url_lower:
        return "https://mastodon.social/"
    if "replit" in name_lower or "replit.com" in url_lower:
        return "https://replit.com/"
    if "gitlab" in name_lower or "gitlab.com" in url_lower:
        return "https://gitlab.com/"
    if "figma" in name_lower or "figma.com" in url_lower:
        return "https://www.figma.com/"
    if "docker" in name_lower or "docker.com" in url_lower:
        return "https://hub.docker.com/"
    if "substack" in name_lower or "substack.com" in url_lower:
        return "https://substack.com/"
    if "letterboxd" in name_lower or "letterboxd.com" in url_lower:
        return "https://letterboxd.com/"
    if "imgur" in name_lower or "imgur.com" in url_lower:
        return "https://imgur.com/"
    if "tumblr" in name_lower or "tumblr.com" in url_lower:
        return "https://www.tumblr.com/"
    if "atlassian" in name_lower or "atlassian.com" in url_lower:
        return "https://id.atlassian.com/"

    if any(k in url_lower for k in ["spclient", "/api/", "/v1/", "/v2/", "check_email", "validate="]):
        from urllib.parse import urlparse
        parsed = urlparse(raw_url)
        if parsed.netloc:
            host = parsed.netloc
            if host.startswith("spclient.wg."):
                host = "open.spotify.com"
            elif host.startswith("api."):
                host = host[4:]
            scheme = parsed.scheme or "https"
            return f"{scheme}://{host}/"
    return raw_url


def run_blackbird_python(target, is_email=False, max_sites=0):
    target = str(target).strip()
    if not target:
        return json.dumps(_json_result(error="Enter an email address or username"))
    try:
        _clear_cli_modules()
        sys.path.insert(0, BLACKBIRD_DIR)
        config = _blackbird_config(bool(is_email), target, max_sites)
        from modules.core.email import verifyEmail
        from modules.core.username import verifyUsername
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            found = verifyEmail(target, config) if is_email else verifyUsername(target, config)
        hits = []
        for item in found or []:
            url = str(item.get("url") or "")
            site_name = item.get("name", "Unknown")
            hits.append({"site": site_name,
                         "category": item.get("category", "general"),
                         "source": "Python: Blackbird upstream",
                         "profileUrl": _clean_profile_url(site_name, url),
                         "statusCode": int(item.get("status_code") or 200),
                         "evidence": "Blackbird upstream reported a match"})
        sites_checked = len(json.load(open(config.EMAIL_LIST_PATH if is_email else config.USERNAME_LIST_PATH, encoding="utf-8")).get("sites", []))
        output = json.dumps(_json_result(sites_checked, hits))
        if config._mobile_dataset_path:
            try:
                os.remove(config._mobile_dataset_path)
            except OSError:
                pass
        if config._mobile_email_dataset_path:
            try:
                os.remove(config._mobile_email_dataset_path)
            except OSError:
                pass
        return output
    except Exception as exc:
        return json.dumps(_json_result(error="Blackbird upstream failed: " + str(exc)))


def run_holehe_email(target, max_concurrent=8):
    """Run Holehe's site checks without its CLI, update checks, or recovery-data output."""
    target = str(target).strip()
    if not target or "@" not in target:
        return json.dumps(_json_result(error="Enter a valid email address"))
    try:
        if VENDOR not in sys.path:
            sys.path.insert(0, VENDOR)
        import httpx
        import trio
        from holehe.core import get_functions, import_submodules, launch_module

        modules = import_submodules("holehe.modules")
        websites = get_functions(modules)
        findings = []
        limiter = trio.Semaphore(max(1, min(int(max_concurrent), 12)))

        async def check_one(website, client):
            async with limiter:
                await launch_module(website, target, client, findings)

        async def run_checks():
            async with httpx.AsyncClient(timeout=12, follow_redirects=True) as client:
                async with trio.open_nursery() as nursery:
                    for website in websites:
                        nursery.start_soon(check_one, website, client)

        trio.run(run_checks)
        hits = []
        issues = []
        for item in findings:
            if not isinstance(item, dict) or not item.get("exists"):
                if isinstance(item, dict) and (item.get("rateLimit") or item.get("error")):
                    name = str(item.get("name") or "Unknown")
                    issues.append({"site": name, "code": 429 if item.get("rateLimit") else 0})
                continue
            name = str(item.get("name") or item.get("domain") or "Unknown")
            domain = str(item.get("domain") or "")

            extras = []
            recovery_email = item.get("emailrecovery")
            if recovery_email and str(recovery_email).strip() not in ("None", "null", ""):
                extras.append(f"Recovery email: {recovery_email}")
            phone_num = item.get("phoneNumber")
            if phone_num and str(phone_num).strip() not in ("None", "null", ""):
                extras.append(f"Recovery phone: {phone_num}")
            others = item.get("others")
            if isinstance(others, dict):
                for k, v in others.items():
                    if v:
                        extras.append(f"{k}: {v}")
            elif others and str(others).strip() not in ("None", "null", ""):
                extras.append(str(others))

            evidence_text = "Holehe module reported exists=true"
            if extras:
                evidence_text += " • " + " | ".join(extras)

            hits.append({
                "site": name,
                "domain": domain,
                "profileUrl": "https://" + domain if domain else "",
                "statusCode": 200,
                "evidence": evidence_text,
            })
        return json.dumps({"sitesChecked": len(websites), "hits": hits, "discarded": [], "issues": issues})
    except Exception as exc:
        return json.dumps(_json_result(error="Holehe email scan failed: " + str(exc)))


def run_tookie_python(username, max_sites=150):
    username = str(username).strip().lstrip("@")
    if not username:
        return json.dumps(_json_result(error="Enter a username"))
    try:
        _clear_cli_modules()
        sys.path.insert(0, TOOKIE_DIR)
        from modules.modules import load_sites, load_user_agents, scan_site
        sites = load_sites()
        if max_sites and int(max_sites) > 0:
            sites = sites[:int(max_sites)]
        agents = load_user_agents(os.path.join(TOOKIE_DIR, "sites", "headers.txt"))
        hits, issues = [], []
        with contextlib.redirect_stdout(io.StringIO()):
            with ThreadPoolExecutor(max_workers=12) as pool:
                futures = {pool.submit(scan_site, entry, username, False, False, agents, True): entry for entry in sites}
                for future in as_completed(futures):
                    entry = futures[future]
                    try:
                        result = future.result()
                    except Exception as exc:
                        issues.append({"site": entry.get("site", "Unknown"), "profileUrl": "", "code": 0, "detail": str(exc)})
                        continue
                    if result and result.get("found"):
                        url = result.get("url") or ""
                        hits.append({"site": entry.get("site", "Unknown"), "category": "social",
                                     "source": "Python: Tookie-OSINT upstream", "profileUrl": url,
                                     "statusCode": int(result.get("status") or 200),
                                     "evidence": "Tookie upstream scan_site confirmed the profile"})
                    elif result and not result.get("status"):
                        issues.append({"site": entry.get("site", "Unknown"),
                                       "profileUrl": result.get("url", ""), "code": 0,
                                       "detail": "Tookie upstream could not reach this site"})
        return json.dumps(_json_result(len(sites), hits, issues))
    except Exception as exc:
        return json.dumps(_json_result(error="Tookie upstream failed: " + str(exc)))


def run_all_python(username, max_sites=150):
    username = str(username).strip().lstrip("@")
    limit = int(max_sites or 0)
    per_tool = max(1, limit // 3) if limit > 0 else 0
    results = [run_sherlock_python(username, per_tool),
               run_tookie_python(username, per_tool),
               run_blackbird_python(username, False, per_tool)]
    return json.dumps([json.loads(result) for result in results])
