# iCast TV – Fire OS test 3

Experimental variant of jqssun/android-airplay-server 0.0.36. Original LICENSE retained.
Do not distribute without following upstream and bundled library license obligations.

## Changes
* UxPlay patch 0010 incorporates the proposed fix from fatihfitil-a11y in [upstream issue #79](https://github.com/jqssun/android-airplay-server/issues/79#issuecomment-6350991016). Direct HTTP(S) HLS URLs are handled by Media3; relative playlist resolution uses its standard behavior. The patch also handles the zero-prefix case and matches the local master path with a query string.
* Visible playback states, load errors (including HTTP status), selected tracks, and terminal Media3 error codes go to the existing Logs screen/export.
* Enable decoder fallback where another compatible decoder exists; include DASH module. No new software video decoder is supplied.
* Default port 7200, separate Android package ID, API minimum 23 unchanged.
* Debug APK built by GitHub Actions; no upstream signing secrets.

Existing installations with a manually saved port retain that setting. Set Server port to 7200 manually if necessary.

## Validation required
1. Verify GitHub Actions build success. Download the artifact ZIP and extract the debug APK.
2. Stop the original AirPlay Server before launching iCast TV (multiple active receivers may cause discovery or port conflicts).
3. Test iPhone screen mirroring, personal MP4/MOV, an authorized HLS stream with relative URIs and query strings, and regression-test sender-relayed HLS.
4. Test buffering recovery, audio/video synchronization, pause/resume, rotation and reconnect.
5. If playback fails, inspect Logs for [LOAD], [ERROR], [STATE], and [TRACK]. Do not share sensitive logs; native messages and logcat may still contain private data.
6. Debug signing keys may differ between CI builds: uninstall the old iCast test build before reinstalling if Android reports a signing conflict. Do not uninstall the upstream app solely to resolve signature conflicts; package IDs differ.

Not yet device-tested. DRM, browser-session-bound streams, hardware codec limits and sender restrictions remain. Issues #73, #72, #82, #84 and #91 require independent reproduction and are not claimed fixed.

## Protocol diagnostics (.3)
- The UI log now records fixed request labels for RTSP SETUP/RECORD/TEARDOWN and HTTP /server-info, /reverse, /play, /stop, /fp-setup2 and HLS requests; it also records numeric response status where available.
- SETUP stream types (110 = mirroring, 96 = audio) and mirror-running callbacks are logged. Routine polling is omitted to avoid flooding the UI.
- Raw headers, URLs, payloads, keys and tokens are not included in these new entries. Other preexisting logs may still contain sensitive data.
- Default receiver name is iCast TV; existing saved settings are unchanged. This diagnostic update does not claim to fix audio-only handover.

## Subtitle support (.4)
- Media3 SubtitleView renders timed text/bitmap cues on top of direct video. Mirroring is unchanged.
- CC menu: automatic (Italian preferred), off, individual supported text tracks, three text sizes. The size is retained while the ViewModel lives; selection is per playback session.
- Logs [SUB] report available text tracks, format/language/support/selection and first received cues, never the actual subtitle text.
- In-stream WebVTT, supported embedded captions and subtitle renditions may work if Media3 receives them. Arbitrary external SRT files and browser-only subtitle overlays are not imported automatically.
- Test: use authorized HLS with text renditions, switch languages, disable subtitles, resize, seek, pause, start a second video with no captions; confirm no stale captions and normal mirroring. Not yet device-tested.

## Fire Stick ARM32 build (.5)
- Debug and release APKs target only armeabi-v7a. The separate arm64 sanitizer variant is retained for development but is not built by the APK workflow.
- CI verifies the native library ABI in the APK before publication. Superseded multi-ABI runs are cancelled where permissions allow. New pushes cancel older runs in the same branch concurrency group.

## In-app updates (.6)
- The Settings section checks GitHub releases (including prereleases), lists a newer version, and downloads an APK only on user action. Startup checks can be disabled.
- Downloads use HTTPS, bounded size, expected release SHA-256 when supplied, pinned iCast signing certificate, matching package and increasing Android version code before launching the Android installer. Installation requires user confirmation.
- CI requires the repository Actions secret ICAST_SIGNING_JSON containing the PKCS12 keystore and password. Never commit or share that secret. The pinned public certificate SHA-256 is 0d2358cf22e2af9f42eb8cc3c7d61c650937bda9e049edf56f42cc92def1e707. No fallback to random debug signing is published.
- Migration from previous ephemeral debug signing may require uninstalling iCast once; this clears its local settings/logs. Export logs first. Future updates signed with the same key can preserve data. Keep a private backup of the secret.
- Validate on Fire OS 6: manual and startup checks, no update, available update, failed network, cancel, installer confirmation, same-key update, settings retained. No on-device verification yet.

## HTTP failure diagnostics (.7)
- The service writes to a shared persistent log independently of the Activity; existing logs are retained. The UI observes the same bounded log and exports a snapshot.
- HTTP load errors report the numeric status, resource type, byte count and exception classes without URLs or credentials. Native errors report only broad categories; no raw messages exported.
- Up to three additional player prepare attempts per session for HTTP 408/429/500/502/503/504 and network connection/timeout errors; no automatic recovery for 401/403/404/410. Media3 retains the media item/position and selection where possible. Live streams and expired URLs may still need restart from sender.
- Test on Fire OS 6: transient HTTP 503 then 200; terminal HTTP 403; log export with Activity destroyed; stop/new stream cancels recovery; update .6 to .7; subtitle and mirroring regressions. Not yet device-tested.

## HTTP 404 recovery (.8)
- When the player reports HTTP 404, rebuild the media source from the original MediaItem, with bounded delays of 2/4/6 seconds (three recovery attempts total per AirPlay video session, shared with transient errors). This causes a new playlist/manifest load for HLS/DASH; VOD requests resume at the last known position, live requests use the new default live position. No changes to authentication URLs or bypassing access controls. 401/403/410 remain terminal.
- Keep the playback snapshot in recovery/buffering state until the player reaches READY. Log [RETRY] mode, resource, live flag, position and result. The failed resource label is the most recently observed non-canceled load error; concurrent loads may affect it.
- Device validation: controlled missing media segment followed by a valid playlist; permanent 404 stops after three attempts; VOD resume; live-window shift; pause/stop/new stream, captions, and in-app update from .7. A permanently missing file or expired URL still requires a new stream from the sender.

## HTTP origin diagnostics (.9)
- [HTTP] and [HTTP-FINAL] record the requested origin category (loopback/LAN/remote), whether the request matches the original media URI, byte range/offset and a classified response content type. The observed URI can differ from the requested URI on redirects, but absence of an observed redirect does not prove there was none on failed opens. No raw URLs or headers in these new logs.
- No speculative URL rewriting or request header changes; no additional retry attempts. Determine whether the failing request is the initial media resource, a downstream segment or local proxy request before making a protocol change.

## HTTP compatibility (.10)
- Settings > Compatibilità HTTP: Standard (default), Safari and Apple TV user-agent profiles; applies only to the next remote HTTP(S) video, not local proxy or mirroring. These are compatibility strings, not authorization credentials.
- Video format: Standard (default), Rileva (optional bounded HEAD probe for extensionless/unknown remote resources), HLS or DASH (explicit MediaItem MIME). HEAD may return different results from GET; no redirect following in probe and unknown results fall back to standard. Forced format should only be selected for a known matching stream.
- No automatic cookie/authorization forwarding or invented referer: the existing AirPlay callback only delivers URL and position and the inspected protocol examples do not establish a safe media-header field. HTTP playback still requires a valid authorized URL. No authentication or DRM bypass.
- Test standard regression, alternate UA on a valid server that negotiates by UA, extensionless HLS/DASH, HEAD failure fallback, cancellation/new play, and Fire OS 6 update. [COMPAT] and [PROBE] contain fixed labels/status only. Not device-tested.

## Experimental sender-assisted HLS (.11)
- Settings > Compatibilità HTTP > FCUP sperimentale (off by default, server restart required). Only HTTP(S) URLs matching the existing /master.m3u8 route and an established reverse channel use the existing sender-assisted playlist exchange. Other HTTP(S) URLs still use direct playback, including extensionless URLs. Existing non-HTTP FCUP route is retained.
- Numeric sender FCUP status, rejected/non-playlist response, and fixed known /play field-presence flags are logged without values. Responses with explicit non-success status or unsupported playlist bodies are not parsed. No authorization headers, cookies, DRM keys, or guessed referers are copied. No claim of support for arbitrary media segments, relative playlist paths or arbitrary URLs; FCUP still requires sender support and direct access to media segments.
- Test .10 standard path first, then .11 opt-in using a supported HLS master URL; check [PROTO] PLAY direct/master/fcup/reverse and FCUP response. Disable if sender does not respond. Lock iPhone only after playback is established. Not device-tested.

## Automatic playback compatibility and TV navigation (.12)
- New HTTP preference keys intentionally default to Auto for upgrades, replacing previously forced format/profile; other settings retained. Standard, Safari, Apple TV, manual HLS/DASH and legacy HEAD detection remain available. FCUP setting is unchanged.
- Auto begins with normal format inference and standard HTTP. If an initial unrecognized-format error identifies HLS/DASH from the final response URI or Content-Type, rebuild the original media item once with the correct MIME. No speculative HLS fallback without evidence. One Safari profile fallback on initial HTTP 404 only. No retry on 401/403 for auto compatibility; normal recovery remains unchanged. No changes to URL, cookies or authorization. No extra HEAD in Auto. Logs [AUTO] show decisions.
- Main navigation tabs now have separate active state and focus ring. Pointer click or DPAD center/Enter release activates; hover and directional movement do not change tab. Other settings already use explicit onClick; sliders keep directional adjustment and text fields keep save-on-blur.
- Device tests: .11 Safari/HLS extensionless redirect, normal MP4, normal HLS, DASH, initial 404 fallback, repeat failures, stop during auto retry, directional navigation without tab change, select/enter click and pointer click. Not device-tested.
