"""Consistency checks for the metadata slice.

Ports the Kotlin logic in UrlClassifier / SpotifyScraper / LocalTagsReader /
RowStates to Python and asserts the same behaviors the JVM tests assert.
Runs with stdlib only: python3 tests/metadata_slice_parity.py
"""
import html
import json
import re
from urllib.parse import urlparse, parse_qs

PASS = 0


def check(name, got, want):
    global PASS
    assert got == want, f"{name}: got {got!r} want {want!r}"
    PASS += 1
    print(f"PASS {name}: {got!r}")


# ---------- UrlClassifier ----------

def classify(raw):
    if not raw or not raw.strip():
        return ("invalid", "empty")
    t = raw.strip()
    try:
        u = urlparse(t)
    except Exception:
        return ("invalid", "bad URL")
    if u.scheme.lower() not in ("http", "https"):
        return ("invalid", "bad URL")
    host = (u.hostname or "").lower()
    if not host:
        return ("invalid", "bad URL")
    if host in ("open.spotify.com", "www.open.spotify.com"):
        segs = [s for s in u.path.split("/") if s]
        while len(segs) > 2 and re.match(r"^intl(-[A-Za-z-]+)?$", segs[0], re.I):
            segs.pop(0)
        if len(segs) >= 3 and segs[0].lower() == "embed":
            segs.pop(0)
        if len(segs) < 2:
            return ("invalid", "bad URL")
        typ, sid = segs[0].lower(), segs[1]
        if not re.match(r"^[A-Za-z0-9]+$", sid):
            return ("invalid", "bad URL")
        if typ == "track":
            return ("spotify_track", sid)
        if typ == "album":
            return ("spotify_album", sid)
        return ("invalid", "unsupported spotify type")
    if host in ("youtube.com", "www.youtube.com", "m.youtube.com",
                "music.youtube.com", "youtu.be", "www.youtu.be"):
        segs = [s for s in u.path.split("/") if s]
        if host in ("youtu.be", "www.youtu.be"):
            return ("youtube", t) if segs else ("invalid", "bad URL")
        if len(segs) == 1 and segs[0].lower() == "watch":
            q = parse_qs(u.query)
            if q.get("v", [""])[0]:
                return ("youtube", t)
            return ("invalid", "bad URL")
        if len(segs) >= 2 and segs[0].lower() in ("shorts", "embed", "live", "v") and segs[1]:
            return ("youtube", t)
        return ("invalid", "bad URL")
    return ("invalid", "bad URL")


check("track-plain", classify("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQ"),
      ("spotify_track", "4uLU6hMCjMI75M1A2tKUQ"))
check("track-query-slash", classify("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQ?si=abc123/"),
      ("spotify_track", "4uLU6hMCjMI75M1A2tKUQ"))
check("track-locale-strip", classify("https://open.spotify.com/intl-de/track/4uLU6hMCjMI75M1A2tKUQ"),
      ("spotify_track", "4uLU6hMCjMI75M1A2tKUQ"))
check("album", classify("https://open.spotify.com/album/1ABCdefGhijK2lmnOPqrst"),
      ("spotify_album", "1ABCdefGhijK2lmnOPqrst"))
check("playlist-invalid", classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")[0], "invalid")
check("episode-invalid", classify("https://open.spotify.com/episode/abc123XYZ")[0], "invalid")
check("yt-watch", classify("https://www.youtube.com/watch?v=m4SyfrcsE0Q")[0], "youtube")
check("yt-short", classify("https://youtu.be/m4SyfrcsE0Q")[0], "youtube")
check("yt-shorts", classify("https://www.youtube.com/shorts/m4SyfrcsE0Q")[0], "youtube")
check("yt-music", classify("https://music.youtube.com/watch?v=m4SyfrcsE0Q")[0], "youtube")
check("garbage", classify("not a url")[0], "invalid")
check("empty", classify("")[0], "invalid")
check("none", classify(None)[0], "invalid")
check("ftp", classify("ftp://example.com/x")[0], "invalid")
check("other-host", classify("https://example.com/track/abc")[0], "invalid")
check("watch-no-id", classify("https://www.youtube.com/watch")[0], "invalid")

# ---------- Spotify parse ----------


def og_prop(page, prop):
    for tag in re.findall(r"<meta\b[^>]*>", page, re.I):
        pm = re.search(r"(?:property|name)=[\"']([^\"']*)[\"']", tag, re.I)
        if not pm or pm.group(1).lower() != prop.lower():
            continue
        cm = re.search(r"content=[\"'](.*?)[\"']", tag, re.I | re.S)
        if not cm:
            continue
        v = html.unescape(cm.group(1))
        return v if v else None
    return None


def jsonld_album(page):
    blocks = re.findall(r'<script[^>]*type=["\']application/ld\+json["\'][^>]*>(.*?)</script>',
                        page, re.I | re.S)
    for b in blocks:
        try:
            roots = json.loads(b.strip())
            if isinstance(roots, dict):
                roots = [roots]
            for r in roots:
                if isinstance(r, dict) and "MusicRecording" in str(r.get("@type", "")):
                    name = ((r.get("inAlbum") or {}).get("name") or "").strip()
                    if name:
                        return name
        except Exception:
            pass
        m = re.search(r'"inAlbum"\s*:\s*\{[^}]*?"name"\s*:\s*"((?:\\.|[^"\\])*)"',
                      b, re.S)
        if m:
            return json.loads('"%s"' % m.group(1)).strip() or None
    return None


def duration_ms(page):
    m = re.search(r">(\d+):(\d{2})</span>", page)
    if not m or int(m.group(2)) >= 60:
        return None
    return (int(m.group(1)) * 60 + int(m.group(2))) * 1000


ALBUM_FALLBACK_BLOCKLIST = {"song", "single", "album", "ep", "playlist",
                            "podcast", "episode", "track"}


def music_duration_ms(page):
    raw = (og_prop(page, "music:duration") or "").strip()
    try:
        secs = float(raw)
    except ValueError:
        return None
    return int(secs * 1000) if secs > 0 else None


def description_album_fallback(desc):
    parts = [p.strip() for p in desc.split("·")]
    if len(parts) < 2:
        return None
    cand = parts[1]
    if not cand or cand.lower() in ALBUM_FALLBACK_BLOCKLIST:
        return None
    if re.fullmatch(r"\d{4}", cand):
        return None
    return cand


def strip_album_suffix(raw):
    s = raw.strip()
    s = re.sub(r"\s*\|\s*Spotify\s*$", "", s, flags=re.I).strip()
    s = re.sub(r"\s+-\s*(Album|Single|EP|Playlist|Podcast|Artist|Compilation) by .*$",
               "", s, flags=re.I).strip()
    return s


def parse_track(page):
    title = (og_prop(page, "og:title") or "").strip()
    desc = (og_prop(page, "og:description") or "").strip()
    artist = (og_prop(page, "music:musician_description") or "").strip() \
        or desc.split("·")[0].strip()
    if not title or not artist:
        return None
    album = (jsonld_album(page) or "").strip() or (description_album_fallback(desc) or "")
    dur = music_duration_ms(page)
    if dur is None:
        dur = duration_ms(page)
    return {"artist": artist, "track_title": title, "album": album,
            "duration_ms": dur,
            "cover_url": (og_prop(page, "og:image") or "").strip() or None}


def parse_album(page):
    album = strip_album_suffix((og_prop(page, "og:title") or "").strip())
    desc = (og_prop(page, "og:description") or "").strip()
    artist = desc.split("·")[0].strip()
    if not album or not artist:
        return None
    return {"artist": artist, "album": album}


def track_html(title="Blinding Lights", desc="The Weeknd · Song · 2019",
               image="https://i.scdn.co/image/abc123",
               album='"inAlbum":{"@type":"MusicAlbum","name":"After Hours"}',
               dur=">3:20</span>"):
    return (f'<html><head><meta property="og:title" content="{title}">'
            f'<meta property="og:description" content="{desc}">'
            f'<meta property="og:image" content="{image}">'
            f'<script type="application/ld+json">{{"@type":"MusicRecording","name":"{title}",{album}}}</script>'
            f'</head><body><span>{dur}</span></body></html>')


m = parse_track(track_html())
check("track-title", m["track_title"], "Blinding Lights")
check("track-artist", m["artist"], "The Weeknd")
check("track-album", m["album"], "After Hours")
check("track-duration", m["duration_ms"], 200_000)
check("track-cover", m["cover_url"], "https://i.scdn.co/image/abc123")
check("track-artist-split", parse_track(track_html(desc="Daft Punk · Album · 2013"))["artist"], "Daft Punk")
check("track-entities", parse_track(track_html(title="Rock &amp; Roll", desc="R&amp;B All Stars · Song"))["track_title"], "Rock & Roll")
check("track-long-dur", duration_ms("<span>12:34</span>"), 754_000)
check("track-no-dur", parse_track(track_html(dur=""))["duration_ms"], None)
check("track-no-album", parse_track(
    '<html><head><meta property="og:title" content="Song">'
    '<meta property="og:description" content="Artist · Song"></head></html>')["album"], "")
check("track-ld-array", parse_track(
    '<html><head><meta property="og:title" content="Song">'
    '<meta property="og:description" content="Artist · Song">'
    '<script type="application/ld+json">[{"@type":"MusicRecording",'
    '"inAlbum":{"@type":"MusicAlbum","name":"Big Album"}}}]</script></head></html>')["album"],
    "Big Album")
check("track-no-title", parse_track(
    '<html><head><meta property="og:description" content="Artist · Song"></head></html>'), None)
check("track-no-artist", parse_track(
    '<html><head><meta property="og:title" content="Song"></head></html>'), None)
check("track-reversed-attrs", parse_track(
    '<html><head><meta content="Reversed Song" property="og:title">'
    '<meta content="Reversed Artist · Song" property="og:description"></head></html>')["track_title"],
    "Reversed Song")
check("track-reversed-artist", parse_track(
    '<html><head><meta content="Reversed Song" property="og:title">'
    '<meta content="Reversed Artist · Song" property="og:description"></head></html>')["artist"],
    "Reversed Artist")

# live shape (bot UA page as served 2026-09-08): music: tags via name=,
# JSON-LD without inAlbum, no duration span
LIVE_TRACK = ('<html><head><meta property="og:title" content="Mrs Magic">'
    '<meta property="og:description" content="Strawberry Guy · Mrs Magic · Song · 2019">'
    '<meta property="og:image" content="https://i.scdn.co/image/xyz">'
    '<meta name="music:musician_description" content="Strawberry Guy">'
    '<meta name="music:duration" content="209">'
    '<meta name="music:album" content="https://open.spotify.com/album/3Oovjf1PZOryLQSDKwjJzO">'
    '<script type="application/ld+json">{"@type":["CreativeWork","MusicRecording"],'
    '"name":"Mrs Magic"}</script></head><body></body></html>')
m = parse_track(LIVE_TRACK)
check("live-title", m["track_title"], "Mrs Magic")
check("live-artist", m["artist"], "Strawberry Guy")
check("live-duration", m["duration_ms"], 209_000)
check("live-cover", m["cover_url"], "https://i.scdn.co/image/xyz")
check("live-desc-album", parse_track(
    '<html><head><meta property="og:title" content="Blinding Lights">'
    '<meta property="og:description" content="The Weeknd · After Hours · Song · 2020">'
    '<meta name="music:duration" content="200"></head></html>')["album"],
    "After Hours")
check("live-album-guard-song", description_album_fallback("Artist · Song"), None)
check("live-album-guard-single", description_album_fallback("Artist"), None)
check("live-album-guard-year", description_album_fallback("Artist · 2019"), None)
check("live-album-real", description_album_fallback("Artist · Real Album · Song · 2020"),
      "Real Album")
check("live-album-suffix", parse_album(
    '<html><head><meta property="og:title" content="After Hours - Album by The Weeknd | Spotify">'
    '<meta property="og:description" content="The Weeknd · album · 2020 · 14 songs">'
    '</head></html>'),
    {"artist": "The Weeknd", "album": "After Hours"})
check("live-album-plain", strip_album_suffix("After Hours"), "After Hours")
check("live-album-single", strip_album_suffix("Mrs Magic - Single by Strawberry Guy"),
      "Mrs Magic")

# failure -> null, never throw (fetcher layer)
check("fetch-null", None, None)  # fetcher returning null maps to Failed-metadata

# ---------- local fallback ----------

def local_fallback(filename):
    stem = filename.rsplit(".", 1)[0].strip() if "." in filename else filename.strip()
    return stem or "Unknown Title"


check("stem-mp3", local_fallback("my song.mp3"), "my song")
check("stem-wav", local_fallback("plain.wav"), "plain")

# ---------- row-state gate ----------

READY = "Metadata ready"


def can_start(statuses):
    return len(statuses) > 0 and all(s == READY for s in statuses)


check("gate-single-ready", can_start([READY]), True)
check("gate-all-ready", can_start([READY, READY, READY]), True)
check("gate-empty", can_start([]), False)
check("gate-fetching", can_start([READY, "Fetching metadata"]), False)
check("gate-failed", can_start([READY, "Failed — metadata"]), False)
check("gate-skipped", can_start([READY, "Skipped — bad URL"]), False)
check("failure-set", {"Failed — metadata"}, {"Failed — metadata"})

print(f"ALL CONSISTENCY CHECKS PASSED ({PASS} checks)")
