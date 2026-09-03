"""Device icon lookup backed by home.miot-spec.com.

Endpoint:
    GET /api/device-icon?model=<model>

Returns:
    {"model": "<model>", "icon_url": "https://cnbj1.fds.api.xiaomi.com/.../1234.png"}
or 404 if the model has no page on miot-spec.com (then the APK falls
back to the per-domain Material icon).

Strategy:
    * In-memory dict (process-local) backed by a small JSON file on disk
      so subsequent restarts don't re-scrape known models.
    * Cache TTL of 7 days — a 404 result is also cached so we don't keep
      hitting miot-spec.com for things that don't exist there.
    * HTML scraping picks the FIRST <img> whose src starts with the
      Xiaomi product CDN. That image is the actual device photo; the
      earlier one is just the integration's logo.
"""
import json
import logging
import re
import time
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException, Query

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/device-icon", tags=["device-icon"])

CACHE_TTL_SECONDS = 7 * 24 * 3600
CACHE_FILE = Path("/opt/cloudlink/data/icon_cache.json")
MIOT_BASE = "https://home.miot-spec.com"
XIAOMI_CDN_RE = re.compile(
    r'<img[^>]+src="(https://cnbj1\.fds\.api\.xiaomi\.com/[^"]+)"'
)
HEADERS = {
    "User-Agent": "CloudLink/1.0 (+https://home.miot-spec.com scraper)",
    "Accept-Language": "zh-CN,en;q=0.8",
}

_cache: dict[str, dict] = {}
_loaded = False


def _load_disk_cache() -> None:
    global _loaded
    if _loaded:
        return
    _loaded = True
    try:
        if CACHE_FILE.exists():
            data = json.loads(CACHE_FILE.read_text(encoding="utf-8"))
            for k, v in data.items():
                _cache[k] = v
            logger.info("Loaded %d icon-cache entries from disk", len(data))
    except Exception as e:
        logger.warning("Failed to load icon cache: %s", e)


def _save_disk_cache() -> None:
    try:
        CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
        CACHE_FILE.write_text(
            json.dumps(_cache, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as e:
        logger.warning("Failed to save icon cache: %s", e)


def _scrape_icon_url(model: str) -> str | None:
    """Fetch the miot-spec page for `model` and return the device photo URL,
    or None if the page doesn't exist / no usable image found."""
    url = f"{MIOT_BASE}/spec/{model}"
    try:
        r = httpx.get(url, timeout=10.0, follow_redirects=True, headers=HEADERS)
        if r.status_code != 200:
            return None
        m = XIAOMI_CDN_RE.search(r.text)
        if not m:
            return None
        # miot-spec HTML-escapes the query string as &amp; — unescape.
        return m.group(1).replace("&amp;", "&")
    except Exception as e:
        logger.warning("Scrape failed for %s: %s", model, e)
        return None


@router.get("")
async def get_device_icon(model: str = Query(..., min_length=1, max_length=200)):
    _load_disk_cache()
    now = time.time()
    entry = _cache.get(model)
    if entry and (now - entry.get("checked_at", 0)) < CACHE_TTL_SECONDS:
        if entry.get("icon_url") is None:
            raise HTTPException(404, f"No icon on miot-spec for {model}")
        return {"model": model, "icon_url": entry["icon_url"]}

    # Miss → scrape, cache, return.
    icon_url = _scrape_icon_url(model)
    _cache[model] = {"icon_url": icon_url, "checked_at": now}
    _save_disk_cache()
    if icon_url is None:
        raise HTTPException(404, f"No icon on miot-spec for {model}")
    return {"model": model, "icon_url": icon_url}
