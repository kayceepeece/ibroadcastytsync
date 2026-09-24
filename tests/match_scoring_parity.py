"""Consistency checks for MatchScoring.kt candidate matching and scoring logic.
Mirrors Kotlin scoreCandidate exactly.
"""
import math, re, unicodedata

def norm_str(s):
    s = s or ""
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    return re.sub(r"\s+", " ", s).strip()

LYRIC = ["letra","lyrics","lyric video","karaoke","bass boosted","sped up","slowed","nightcore","8d audio","reverb","pitched"]
VIDEO = ["official video","video oficial","official music video","videoclip","video clip","official mv","(mv)"]
AUDIO = ["official audio","audio oficial","audio only","full song","(audio)","[audio]","| audio"]
LIVE = ("remix","live","en vivo","en directo","ao vivo","concert","concierto","en concierto","en gira","behind the scenes")
SPOT_LIVE = ("remix","live","en vivo","en directo","ao vivo","concierto","en concierto")

def score(title, artist, duration_ms, c):
    s = 0.0
    target = duration_ms/1000.0 if duration_ms else None
    vid_l = c["title"].lower()
    title_l = title.lower()
    title_n = norm_str(title)
    akey = artist.split(",")[0].strip().lower()
    if target is not None:
        d = abs((c.get("duration") or 0) - target)
        if d <= 2: s += 10.0 - d*2
        elif d <= 5: s += 2.0
        if (c.get("duration") or 0) > target+15: s -= 4.0
        if (c.get("duration") or 0) > target+30: s -= 4.0
    feat = re.sub(r"\s*[\(\[](feat|ft)\..*", "", title_n, flags=re.I).strip()
    m = re.search(r"\s*\(([^)]+)\)\s*$", feat)
    base = feat[:m.start()].strip() if m else feat
    vkw = norm_str(m.group(1)) if m else None
    vid_n = norm_str(c["title"])
    if base and re.search(rf"\b{re.escape(base)}\b", vid_n): s += 5.0
    if vkw:
        s += 4.0 if vkw in vid_n else -5.0
    if akey and re.search(rf"\b{re.escape(akey)}\b", vid_n): s += 1.0
    if akey and norm_str(akey) in norm_str(c.get("channel","")): s += 3.0
    v = c.get("view_count",0) or 0
    if v: s += min(math.log10(v), 3.0 if target is None else 5.0)
    if any(k in vid_l for k in LYRIC): s -= 3.0
    if any(k in vid_l for k in VIDEO): s -= 4.0
    if any(k in vid_l for k in AUDIO): s += 4.0
    if vkw is None and any(k in vid_l for k in LIVE) and not any(k in title_l for k in SPOT_LIVE): s -= 5.0
    return s

def best(title, artist, dur, cands):
    if not cands: return None
    r = sorted([(score(title,artist,dur,c),c) for c in cands], key=lambda x:-x[0])
    return None if r[0][0] <= 0 else f"https://music.youtube.com/watch?v={r[0][1]['id']}"

def check(name, got, want):
    assert got == want, f"{name}: got {got} want {want}"
    print(f"PASS {name}: {got}")

# 1. duration proximity wins
c = [
    {"id":"a","title":"Song - Artist Official Audio","channel":"Artist","duration":213,"view_count":1000},
    {"id":"b","title":"Song - Artist Official Video","channel":"Artist","duration":245,"view_count":10_000_000},
]
check("duration+audio-beats-video", best("Song","Artist",213000,c), "https://music.youtube.com/watch?v=a")

# 2. lyric penalty
c = [
    {"id":"a","title":"Song Lyrics","channel":"X","duration":213,"view_count":5_000_000},
    {"id":"b","title":"Song Official Audio","channel":"Artist","duration":213,"view_count":1000},
]
check("lyric-penalty", best("Song","Artist",213000,c), "https://music.youtube.com/watch?v=b")

# 3. live ES penalty (WR-07)
c = [
    {"id":"live","title":"Song En Directo Concierto","channel":"Fan","duration":213,"view_count":9_000_000},
    {"id":"aud","title":"Song Official Audio","channel":"Artist","duration":213,"view_count":2000},
]
check("live-es-penalty", best("Song","Artist",213000,c), "https://music.youtube.com/watch?v=aud")

# 4. version qualifier reward/penalty
c = [
    {"id":"r","title":"Song (Remix) Official Audio","channel":"Artist","duration":200,"view_count":100},
    {"id":"o","title":"Song Official Audio","channel":"Artist","duration":200,"view_count":100},
]
check("version-match", best("Song (Remix)","Artist",200000,c), "https://music.youtube.com/watch?v=r")
check("version-absent-penalty", best("Song","Artist",200000,[
    {"id":"r","title":"Song (Remix)","channel":"X","duration":200,"view_count":100},
    {"id":"o","title":"Song Official Audio","channel":"Artist","duration":200,"view_count":100},
]), "https://music.youtube.com/watch?v=o")

# 5. word boundary Run vs Running
c = [{"id":"x","title":"Running Official Audio","channel":"Artist","duration":180,"view_count":100}]
s_run = score("Run","Artist",180000,c[0])
c2 = [{"id":"x","title":"Run Official Audio","channel":"Artist","duration":180,"view_count":100}]
s_exact = score("Run","Artist",180000,c2[0])
assert s_exact > s_run, f"word-boundary failed {s_exact} vs {s_run}"
print(f"PASS word-boundary: exact={s_exact:.2f} running={s_run:.2f}")

# 6. no-match none
check("empty-none", best("Song","Artist",200000,[]), None)

# 7. view cap no-duration (viral video must not dominate audio)
c = [
    {"id":"viral","title":"Song Official Video","channel":"Big","duration":0,"view_count":50_000_000},
    {"id":"aud","title":"Song Official Audio","channel":"Artist","duration":0,"view_count":5000},
]
check("view-cap-no-duration", best("Song","Artist",0,c), "https://music.youtube.com/watch?v=aud")

# 8. semitones ratio
import math as m
st = 12.0*m.log(432.0/440.0,2)
assert abs(st - (-0.318)) < 0.005, st
print(f"PASS semitones {st:.4f}")

print("ALL CONSISTENCY CHECKS PASSED")
