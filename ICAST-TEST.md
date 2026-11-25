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
