# Android device verification — 01 / 02 / 03 (+05 notes)

Date: 2026-09-08. Status: harness + interfaces landed, device runs PENDING.
Gate: strict — all three green on real arm64 or pause pipeline UI work.

## 01 — yt-dlp anonymous download (GREEN 2026-09-08, TECNO LI6 arm64 Android 15)

- `youtubedl-android`: `io.github.junkfood02.youtubedl-android:library:0.18.1` (+ `:ffmpeg:0.18.1`)
- Bundled `yt-dlp` at install: `2025.11.12` (warns older than 90 days, all clients fail on test video)
- In-app update `UpdateChannel.STABLE` → `2026.08.19 DONE`
- After update, download OK: `https://www.youtube.com/watch?v=m4SyfrcsE0Q` → `tb_0bd433bc_Strawberry Guy - Mrs Magic (Official Audio).mp3`, 4.7M, `player_client=android` (ios failed, android won; web not reached)
- Output dir: app-specific `getExternalFilesDir("youtubedl-android")` (public Downloads blocked by scoped storage)
- Flags: `--no-playlist --no-check-certificate --extractor-args youtube:player_client=<ios|android|web> -x --audio-format mp3 --audio-quality 192K -o <tmp>/tb_<token>_ cases verified:` official-audio beats official-video`, `lyric penalty`, `live ES penalty`, `version qualifier`, `no-match none`, `view cap`, `word-boundary Run`, `feat strip`.

Verification app steps:
1. Minimal Compose activity with one button.
2. Call `YtDlpEngine.download(DownloadRequest(queryOrUrl=<test video URL>, tokenPrefix=tb_<8hex>))`.
3. Flags: `--no-playlist --no-check-certificate -x --audio-format mp3 --audio-quality 192K -o <tmp>/tb_<token>_ cases verified:` official-audio beats official-video`, `lyric penalty`, `live ES penalty`, `version qualifier`, `no-match none`, `view cap`, `word-boundary Run`, `feat strip`.

## 02 — rubberband retune (FILTER CHECK DONE 2026-09-08, retune blocked)

- Bundled `libffmpeg.so` (youtubedl-android 0.18.1) `-filters` grep: `rubberband ABSENT`.
- Native libs: `libpython.so, libffmpeg.so, libffmpeg.zip.so, libffprobe.so, libpython.zip.so, libqjs.so`.
- Per SPEC Q6 gate: 432Hz disables with explanation on this build. No silent fallback.
- Full retune needs full-gpl source build (not bundled). Decision: ship v1 440-only or budget that build.
- Semitones math verified: `-0.3177`.

## 03 — OAuth upload (GREEN 2026-09-08; token+library+upload proven)

- Device-code flow works: approve in browser → `TOKENS ok exp=3599s`.
- `library OK tracks=79 playlists=9` over Bearer + envelope on first poll.
- Upload proven: `upload http=200 md5=19730a0f7d92ec3934f098d0b928c877 body={"result":true,...uploaded successfully and is being processed."}` via Bearer multipart to upload host.
- Re-proven on `ibytsync.android` after rename (track id 545737811) with scaled timeout (`max(600s, bytes/50KB/s)`, cap 900s) + counting-sink progress + one retry. Flush-ordering bug in progress wrapper caught and fixed on-device.
- Timeouts: 30s call window flaked twice on poll; 120s window green. Keep long timeouts for auth/upload calls.

- 01 command: minimal activity calls `YtDlpEngine.download` with these flags; `rc==0 + no file = failure`, fall through player clients `ios → android → web`, log variant.
- 02 command: `rubberband=pitch=-0.318` via `FfmpegEngine.retune`, re-encode 192k, verify duration/sr/tags; absent filter disables toggle.
- 03 flow: `GET device/code` → show URL/QR → poll token → Bearer library fetch → multipart upload → duplicate + playlist append.
- 05 is pure JVM, no device needed; covered by the JVM suite plus `tests/`.

## 02b — rubberband source-build research (RESEARCH 2026-09-08, no device run, no build files touched)

Method note: Context7 CLI was unreachable from this host (`fetch failed` on both
`npx ctx7@latest library` calls, 2 of max 3 used). Findings below are verified
against primary sources instead: ffmpeg-kit-next README + `android.sh` (raw GitHub),
`android.sh` / NDK-Compatibility / Packages wikis, upstream breakfastquay COMPILING.md,
community size/build reports.

### Q1 — FFmpegKitNext source build for arm64 full-gpl + librubberband

- Repo: `github.com/arthenica/ffmpeg-kit-next` — the official continuation of
  FFmpegKit. It publishes NO prebuilt Maven/CocoaPods artifacts by design; local
  source build (Nix recommended, plain script also supported) is the only route.
- Pinned versions (wiki Versions): `9.0.0` = FFmpeg 9.0.1 (2026-08-24, latest);
  `8.1.1` = FFmpeg 8.1.2; also `7.1.0`, `6.1.1` lines. Recommend `8.1.1` (mature)
  or `9.0.0` (latest) — pin one before building.
- Toolchain (wiki NDK-Compatibility): NDK `r27d (27.3.13750724)` tested for every
  release incl. 9.0.0; default API level 24. NDK r27 is 16KB-page-safe for arm64 —
  verify on the produced .so (commands below).
- Exact flags (wiki `android.sh` + GPL gate in `android.sh` source, which aborts if
  any of x264/xvidcore/x265/vidstab/rubberband is enabled without `--enable-gpl`):

```bash
git clone https://github.com/arthenica/ffmpeg-kit-next.git && cd ffmpeg-kit-next
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/27.3.13750724
./nix-android.sh -p android-r27d --enable-gpl --enable-lib-all --enable-lib-rubberband \
  --disable-arch-arm-v7a --disable-arch-arm-v7a-neon \
  --disable-arch-x86 --disable-arch-x86-64 --api-level=24
# no-Nix fallback: same flags via ./android.sh after installing host
# prereqs (wiki Android-Prerequisites). Default is 5 archs + zero external libs,
# so the --disable-arch-* flags (arm64-only) and --enable-lib-all matter.
```

- Output: single AAR under `prebuilt/` published to a local Maven repo as
  `com.arthenica:ffmpeg-kit-next:<ver>` (+ `smart-exception-java:0.2.1` runtime dep).
- Migration caveat: Next's Android API is Kotlin (old ffmpeg-kit was Java) and the
  artifact/package differs — `FfmpegEngine` call sites need porting; confirm package
  names under `android/` before estimating that work.
- Size (ESTIMATES from community reports — measure post-build, do not quote):
  arm64-only full-equivalent AAR ≈ 25–35MB; APK native delta ≈ +25–30MB
  uncompressed (≈40% less on Play download). Rubberband incremental ≈ +1–2MB
  (librubberband is small). Basis: necxa-rescue full-gpl ≈108MB all-ABI vs
  min-gpl ≈10MB AAR; `ffmpeg-kit-https` 30→130MB APK report (all ABIs);
  minimal arm64-only AAR ≈13MB. NOTE: youtubedl-android already ships its own
  `libffmpeg.so` — this path temporarily ships TWO FFmpegs until its `:ffmpeg`
  artifact is dropped.
- Build time (ESTIMATE): arm64-only full ≈ 30–60 min on 8 cores; all 5 default
  archs = hours. Basis: community 16KB minimal build 15–30 min.
- 16KB verification (SDK 35 gate):
  `$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump -p libffmpegkit.so | grep LOAD`
  and `zipalign -v -c -P 16 app.apk`. Fallback if misaligned:
  `--extra-ldflags="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"`.

### Q2 — prebuilt Maven fork shipping rubberband: NONE FOUND (dead end, stop hunting)

- Upstream `full-gpl` excluded rubberband by design (Packages wiki matrix lists
  vid.stab/x264/x265/xvidcore only; rubberband sits on the "External Libraries Not
  Included In Packages" wiki page — source-build-only).
- `ffmpegkit-maintained/ffmpeg` (`dev.ffmpegkit-maintained`, SDK 35 + enforced 16KB,
  NDK r27c, arm64-only, FFmpeg 6.0/7.1/8.1 lines): `-gpl` variants add only
  x264/x265/xvid/vidstab — no rubberband in the published matrix.
- 16KB forks (`minorlai`, `moizhassankh`, `Visu333`): full/min rebuilds, no rubberband.
- Conclusion: no `implementation`-one-liner exists for the `rubberband` filter.
  Source build (Q1) is mandatory for `-filter:a rubberband=pitch=...`.

### Q3 — standalone librubberband via NDK (alternative, keeps youtubedl ffmpeg)

- Upstream `breakfastquay/rubberband` COMPILING.md: Meson is the only supported
  build; `otherbuilds/Android.mk` is stale; `koendv/rubberband` prebuilts are
  2018/NDK-r10e — unusable for SDK 35 + 16KB.
- Leanest viable path: compile upstream `single/RubberBandSingle.cpp`
  (single-file build) directly into the app CMake target with NDK r27
  (`-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24`), JNI over the
  `RubberBandStretcher` API: decode PCM → pitch-shift 440→432 offline →
  re-encode with the existing ffmpeg. Cost ≈ +1–2MB, zero FFmpeg rebuild —
  but custom JNI work instead of a one-line ffmpeg filter.
- Does NOT dodge licensing (below). Prefer Q1 unless APK budget forces the split.

### Licensing warning (applies to Q1 AND Q3)

- Rubberband is GPL: `--enable-gpl` relicenses the whole bundle to GPL-3.0
  (Next README §15; `android.sh` enforces the flag). Standalone static/dynamic
  linking contaminates equally. Closed-source distribution needs a commercial
  Rubber Band licence (breakfastquay) or legal sign-off — otherwise the SPEC Q6
  gate stands: ship 440-only with explanation, no silent fallback.
- Unverified pointer only: SoundTouch is reportedly LGPL and does
  tempo-preserving pitch shift — needs its own probe build if GPL is a hard blocker.

### Recommended path

1. v1 ships 440-only (existing gate, unchanged).
2. In parallel: source-build FFmpegKitNext `8.1.1` (or `9.0.0`) arm64-only with
   `--enable-gpl --enable-lib-all --enable-lib-rubberband` via Nix profile
   `android-r27d`; verify `-filters | grep rubberband` + 16KB alignment on the
   TECNO LI6; measure the real APK delta.
3. If GPL blocks distribution: probe SoundTouch-standalone or price the
   commercial rubberband licence. Do not spend more time hunting prebuilts.
4. Later: drop youtubedl-android's `:ffmpeg` artifact so only one FFmpeg ships.
