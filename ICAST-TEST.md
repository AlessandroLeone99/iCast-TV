# iCast TV – Fire OS test 1

Private experimental variant of jqssun/android-airplay-server 0.0.36. Original LICENSE retained.
Do not distribute without following upstream and bundled library license obligations.

## Changes
* UxPlay patch 0010 incorporates the proposed fix from fatihfitil-a11y in [upstream issue #79](https://github.com/jqssun/android-airplay-server/issues/79#issuecomment-6350991016). Direct HTTP(S) HLS URLs are handled by Media3; relative playlist resolution uses its standard behavior. The patch also handles the zero-prefix case and matches the local master path with a query string.
* Visible playback states, load errors (including HTTP status), selected tracks, and terminal Media3 error codes go to the existing Logs screen/export.
* Enable decoder fallback where another compatible decoder exists; include DASH module. No new software video decoder is supplied.
* Default port 7100, separate Android package ID, API minimum 23 unchanged.
* Debug APK build uploaded as a private Actions artifact, no release publishing or upstream signing secrets.

## Validation required
1. Verify GitHub Actions build success. Download the artifact ZIP and extract the debug APK.
2. Stop the original AirPlay Server before launching iCast TV (both may otherwise compete for port 7100).
3. Test iPhone screen mirroring, personal MP4/MOV, an authorized HLS stream with relative URIs and query strings, and regression-test sender-relayed HLS.
4. Test buffering recovery, audio/video synchronization, pause/resume, rotation and reconnect.
5. If playback fails, inspect Logs for [LOAD], [ERROR], [STATE], and [TRACK]. Do not share sensitive logs; native messages and logcat may still contain private data.
6. Debug signing keys may differ between CI builds: uninstall the old iCast test build before reinstalling if Android reports a signing conflict. Do not uninstall the upstream app solely to resolve signature conflicts; package IDs differ.

Not yet device-tested. DRM, browser-session-bound streams, hardware codec limits and sender restrictions remain. Issues #73, #72, #82, #84 and #91 require independent reproduction and are not claimed fixed.
