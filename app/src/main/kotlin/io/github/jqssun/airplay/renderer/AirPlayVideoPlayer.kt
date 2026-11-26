package io.github.jqssun.airplay.renderer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.Tracks
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.common.MimeTypes
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.exoplayer.ExoPlayer

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// One concrete text track from the current Media3 timeline.
data class SubtitleTrack(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val supported: Boolean,
    val selected: Boolean,
)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
@androidx.annotation.OptIn(UnstableApi::class)
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null
    private var clientGain = 1f

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onFatalError: ((String) -> Unit)? = null
    private var recoveryAttempts = 0
    private var recovery: Runnable? = null
    private var recoveryPositionMs = 0L
    private var recoveryDurationMs = 0L
    private var wasReady = false
    private var lastKnownLive = false
    private var recoveryInProgress = false
    private var recoveryTargetMs = C.TIME_UNSET
    private var lastFailedResource = "unknown"
    private var lastHttpDetails = "origin=unknown"
    private var sourceUri: Uri? = null
    private var autoProfile = false
    private var autoFormat = false
    private var profileSwitched = false
    private var formatSwitched = false
    private var observedManifestMime: String? = null
    private var playbackHttpFactory: DefaultHttpDataSource.Factory? = null
    private var initialStartMs = 0L
    private var autoSwitchPending = false
    private var probeTask: Future<*>? = null
    private var sessionId = 0L
    private val probeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "icast-http-probe").apply { isDaemon = true } }
    var onDiagnostic: ((String) -> Unit)? = null

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks = _subtitleTracks.asStateFlow()
    private val _subtitleCues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleCues = _subtitleCues.asStateFlow()
    private var firstCueLogged = false

    // A null track selects automatic Italian-preferred captions unless disabled.
    fun selectSubtitle(track: SubtitleTrack?, disabled: Boolean = false) = mainHandler.post {
        val p = player ?: return@post
        if (track != null && p.currentTracks.groups.none {
                it.mediaTrackGroup == track.group && it.type == C.TRACK_TYPE_TEXT &&
                    track.index in 0 until it.length && it.isTrackSupported(track.index)
            }) {
            diagnostic("[SUB] requested track no longer available")
            return@post
        }
        val builder = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
            .setPreferredTextLanguage("it")
        if (track != null && !disabled) {
            builder.setOverrideForType(TrackSelectionOverride(track.group, track.index))
        }
        _subtitleCues.value = emptyList()
        firstCueLogged = false
        p.trackSelectionParameters = builder.build()
        diagnostic("[SUB] selection=" + when {
            disabled -> "off"
            track != null -> "manual"
            else -> "auto"
        })
    }

    private fun reportSubtitleTracks(tracks: Tracks) {
        val found = mutableListOf<SubtitleTrack>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val language = format.language?.take(24)?.filter { it.isLetterOrDigit() || it == '-' }
                val label = listOfNotNull(format.label?.take(80), language?.takeIf { it.isNotBlank() })
                    .distinct().joinToString(" · ").ifBlank { "Track ${found.size + 1}" }
                val supported = group.isTrackSupported(index)
                val selected = group.isTrackSelected(index)
                found.add(SubtitleTrack(group.mediaTrackGroup, index, label, supported, selected))
                diagnostic("[SUB] track=${found.size} lang=${language ?: "und"} mime=${format.sampleMimeType} supported=$supported selected=$selected")
            }
        }
        _subtitleTracks.value = found
        if (found.isEmpty()) diagnostic("[SUB] no text tracks advertised by stream")
    }

    private fun diagnostic(message: String) {
        Log.i(TAG, message)
        onDiagnostic?.invoke(message)
    }

    // Do not log exception messages, request URLs, cookies or response bodies:
    // signed stream URLs can contain authentication tokens.
    private fun describeCause(error: Throwable): String {
        return generateSequence(error) { it.cause }
            .take(8)
            .joinToString(" -> ") { cause ->
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    "${cause.javaClass.simpleName}(HTTP ${cause.responseCode})"
                } else {
                    cause.javaClass.simpleName
                }
            }
    }

    private fun httpFailure(error: Throwable): HttpDataSource.InvalidResponseCodeException? =
        generateSequence(error) { it.cause }.take(12)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

    private fun httpStatus(error: Throwable): Int? = httpFailure(error)?.responseCode

    private fun origin(uri: Uri): String {
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.trim('[', ']') ?: return "unknown"
        return when {
            host == "localhost" || host.startsWith("127.") || host == "::1" -> "loopback"
            host.startsWith("192.168.") || host.startsWith("10.") ||
                Regex("""172\.(1[6-9]|2[0-9]|3[01])\..*""").matches(host) ||
                host.endsWith(".local") -> "LAN"
            else -> "remote"
        }
    }

    // Only fixed labels, booleans and offsets: no header values, URL paths, query
    // strings, response bodies, or site names. A failed request can redirect before
    // returning a status; the exception retains the requested URI, not always the
    // final response URI.
    private fun httpDetails(error: Throwable, reportedUri: Uri): String {
        val failure = httpFailure(error) ?: return "origin=${origin(reportedUri)}"
        val spec = failure.dataSpec
        val requested = spec.uri
        val headers = failure.headerFields
        val mime = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        val content = when {
            mime == null -> "unknown"
            mime.contains("mpegurl") -> "HLS"
            mime == "application/dash+xml" -> "DASH"
            mime.startsWith("video/") || mime.startsWith("audio/") -> "media"
            mime == "text/html" -> "html"
            mime == "application/json" -> "json"
            mime.startsWith("text/") -> "text"
            else -> "other"
        }
        val range = spec.position > 0 || spec.length != C.LENGTH_UNSET.toLong()
        return "origin=${origin(requested)} observed=${origin(reportedUri)} initial=${requested == sourceUri} " +
            "redirectObserved=${reportedUri != requested} range=$range offset=${spec.position} response=$content"
    }

    private fun resourceKind(uri: Uri): String {
        val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> "HLS playlist"
            path.endsWith(".mpd") -> "DASH manifest"
            path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") -> "media segment"
            path.endsWith(".vtt") -> "subtitle"
            path.endsWith(".mp4") -> "MP4"
            else -> "other"
        }
    }

    private fun manifestMime(uri: Uri, headers: Map<String, List<String>> = emptyMap()): String? {
        val type = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        return when {
            type?.contains("mpegurl") == true -> MimeTypes.APPLICATION_M3U8
            type == MimeTypes.APPLICATION_MPD -> MimeTypes.APPLICATION_MPD
            resourceKind(uri) == "HLS playlist" -> MimeTypes.APPLICATION_M3U8
            resourceKind(uri) == "DASH manifest" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // Rebuild only the original media item, not the receiver or player. This keeps
    // the AirPlay session, surface and track settings. No credentials/URL changes.
    private fun tryAutoCompatibility(p: ExoPlayer, error: PlaybackException): Boolean {
        if (wasReady || autoSwitchPending) return false
        val item = p.currentMediaItem ?: return false
        val status = httpStatus(error)
        val failure = httpFailure(error)
        val parsing = generateSequence<Throwable>(error) { it.cause }.take(12)
            .filterIsInstance<UnrecognizedInputFormatException>().firstOrNull()
        val inferred = if (parsing != null) observedManifestMime ?: manifestMime(parsing.uri) else null
        val currentMime = item.localConfiguration?.mimeType
        val changeFormat = autoFormat && !formatSwitched && inferred != null && inferred != currentMime
        // A single alternate client profile for an initial 404, not authentication
        // errors or missing segments once playback is underway.
        val changeProfile = autoProfile && !profileSwitched && status == 404 &&
            failure?.dataSpec?.uri == sourceUri && playbackHttpFactory != null
        if (!changeFormat && !changeProfile) return false
        if (changeFormat) formatSwitched = true
        if (changeProfile) profileSwitched = true
        autoSwitchPending = true
        recoveryInProgress = true
        recoveryPositionMs = p.currentPosition.takeIf { it > 0 } ?: initialStartMs
        recoveryDurationMs = 0L
        val id = sessionId
        diagnostic("[AUTO] switch profile=${if (changeProfile) "safari" else "unchanged"} format=${if (changeFormat) if (inferred == MimeTypes.APPLICATION_M3U8) "HLS" else "DASH" else "unchanged"}")
        // Leave the error callback before re-preparing the player.
        mainHandler.post {
            if (sessionId == id && player === p) {
                autoSwitchPending = false
                if (changeProfile) playbackHttpFactory?.setUserAgent(profileAgent("safari"))
                val updated = if (changeFormat) item.buildUpon().setMimeType(inferred).build() else item
                p.setMediaItem(updated, recoveryPositionMs)
                p.prepare()
            }
        }
        return true
    }

    private fun recoverOrEnd(error: PlaybackException) {
        val p = player ?: return
        if (autoSwitchPending || tryAutoCompatibility(p, error)) return
        val status = httpStatus(error)
        val missing = status == 404
        val transient = if (status != null) status in setOf(404, 408, 429, 500, 502, 503, 504)
            else error.errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        val item = p.currentMediaItem
        if (transient && item != null && recoveryAttempts < 3) {
            if (recovery != null) return
            recoveryAttempts++
            val live = lastKnownLive || p.isCurrentMediaItemLive
            if (!recoveryInProgress) {
                recoveryPositionMs = p.currentPosition.coerceAtLeast(0)
                recoveryDurationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                recoveryTargetMs = recoveryPositionMs
            }
            // A live playlist may no longer contain the old segment. Re-enter at the
            // manifest's default live position instead of asking for that segment again.
            if (missing && live) recoveryTargetMs = C.TIME_UNSET
            recoveryInProgress = true
            val waitMs = when (status) {
                429 -> 15000L * recoveryAttempts
                404 -> 2000L * recoveryAttempts
                else -> 2000L * recoveryAttempts
            }
            val mode = if (missing) "reload-source" else "prepare"
            diagnostic("[RETRY] attempt=$recoveryAttempts/3 HTTP=${status ?: "-"} resource=$lastFailedResource mode=$mode live=$live delay=${waitMs}ms position=${recoveryPositionMs}ms")
            val retry = Runnable {
                recovery = null
                if (player === p) {
                    if (missing) {
                        // Replace the media source, not just retry the failed load.
                        // Preserve the original URL (including its authentication query),
                        // playback parameters and track selection. Never invent a new URL.
                        _subtitleCues.value = emptyList()
                        diagnostic("[RETRY] reload source target=${if (recoveryTargetMs == C.TIME_UNSET) "live-default" else "${recoveryTargetMs}ms"}")
                        p.setMediaItem(item, recoveryTargetMs)
                    }
                    p.prepare()
                }
            }
            recovery = retry
            mainHandler.postDelayed(retry, waitMs)
            return
        }
        recoveryInProgress = false
        val explanation = when (status) {
            401, 403 -> "Accesso rifiutato dal server (HTTP $status). Il link potrebbe essere scaduto. Riavvia il video dal dispositivo sorgente."
            404 -> "Risorsa video non disponibile (HTTP 404). Tentativi di recupero: $recoveryAttempts. Riavvia il video dal dispositivo sorgente."
            410 -> "Risorsa video non disponibile (HTTP 410). Riavvia il video dal dispositivo sorgente."
            null -> "Riproduzione interrotta: ${error.errorCodeName}."
            else -> "Riproduzione interrotta: HTTP $status."
        }
        diagnostic("[FATAL] HTTP=${status ?: "-"} resource=$lastFailedResource attempts=$recoveryAttempts position=${p.currentPosition}ms")
        if (status != null) diagnostic("[HTTP-FINAL] $lastHttpDetails")
        onFatalError?.invoke(explanation)
        onEnded?.invoke()
    }

    private val loadDiagnostics = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val kind = resourceKind(loadEventInfo.uri).let {
                if (it == "other" && mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) "manifest"
                else if (it == "other" && mediaLoadData.dataType == C.DATA_TYPE_MEDIA) "media"
                else it
            }
            // The terminal progressive extractor error is often marked canceled.
            // It still carries the redirect URI and response headers we need.
            if (!wasReady && loadEventInfo.dataSpec.uri == sourceUri &&
                error is UnrecognizedInputFormatException) {
                observedManifestMime = manifestMime(loadEventInfo.uri, loadEventInfo.responseHeaders)
                diagnostic("[AUTO] observed manifest=${when(observedManifestMime) { MimeTypes.APPLICATION_M3U8 -> "HLS"; MimeTypes.APPLICATION_MPD -> "DASH"; else -> "unknown" }} redirected=${loadEventInfo.uri != loadEventInfo.dataSpec.uri}")
            }
            if (!wasCanceled) {
                lastFailedResource = kind
                lastHttpDetails = httpDetails(error, loadEventInfo.uri)
            }
            if (httpStatus(error) != null) diagnostic("[HTTP] status=${httpStatus(error)} ${httpDetails(error, loadEventInfo.uri)}")
            diagnostic("[LOAD] type=${mediaLoadData.dataType} resource=$kind HTTP=${httpStatus(error) ?: "-"} elapsed=${loadEventInfo.loadDurationMs}ms bytes=${loadEventInfo.bytesLoaded} canceled=$wasCanceled cause=${describeCause(error)}")
        }
    }

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            diagnostic("[ERROR] ${error.errorCodeName} (${error.errorCode}) cause=${describeCause(error)}")
            recoverOrEnd(error)
        }
        override fun onPlaybackStateChanged(state: Int) {
            val label = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> state.toString()
            }
            diagnostic("[STATE] $label position=${player?.currentPosition ?: 0}ms buffered=${player?.bufferedPosition ?: 0}ms")
            if (state == Player.STATE_READY) {
                wasReady = true
                lastKnownLive = player?.isCurrentMediaItemLive == true
                if (recoveryInProgress) {
                    recoveryInProgress = false
                    diagnostic("[RETRY] recovered position=${player?.currentPosition ?: 0}ms live=$lastKnownLive")
                }
            }
            if (state == Player.STATE_ENDED) onEnded?.invoke()
        }
        override fun onTracksChanged(tracks: Tracks) {
            reportSubtitleTracks(tracks)
            for (group in tracks.groups) {
                for (index in 0 until group.length) {
                    if (!group.isTrackSelected(index)) continue
                    val format = group.getTrackFormat(index)
                    diagnostic("[TRACK] mime=${format.sampleMimeType} codec=${format.codecs} size=${format.width}x${format.height} audio=${format.channelCount}ch")
                }
            }
        }
        override fun onCues(cueGroup: CueGroup) {
            _subtitleCues.value = cueGroup.cues.toList()
            if (cueGroup.cues.isNotEmpty() && !firstCueLogged) {
                firstCueLogged = true
                diagnostic("[SUB] first cues received count=${cueGroup.cues.size}")
            }
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // duration is usually established here (esp. hls): report so the held /play releases
            _reportPlaybackInfo()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            onTitle?.invoke(mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            onVideoSize?.invoke(width, videoSize.height, width.toFloat() / videoSize.height)
        }
    }

    private fun profileAgent(profile: String): String? = when (profile) {
        "safari" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
        "appletv" -> "AppleCoreMedia/1.0.0 (Apple TV; U; CPU OS 18_0 like Mac OS X; en_us)"
        else -> null
    }

    // A best-effort HEAD probe, only on explicit opt-in, remote HTTP(S) streams
    // without a recognized manifest suffix. No body download, cookies, or guessed
    // referer. Do not follow redirects in this diagnostic probe.
    private fun detectMime(uri: Uri, agent: String?): Pair<String?, String> {
        var connection: HttpURLConnection? = null
        return try {
            val c = URL(uri.toString()).openConnection() as HttpURLConnection
            connection = c
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = false
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.useCaches = false
            agent?.let { c.setRequestProperty("User-Agent", it) }
            val status = c.responseCode
            val type = c.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
            val mime = if (status in 200..299) when (type) {
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                else -> null
            } else null
            mime to "status=$status detected=${when(mime) { MimeTypes.APPLICATION_M3U8 -> "HLS"; MimeTypes.APPLICATION_MPD -> "DASH"; else -> "unknown" }}"
        } catch (e: Exception) {
            null to "failed=${e.javaClass.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }

    fun play(location: String, startPositionSeconds: Float, requestedProfile: String = "standard", requestedFormat: String = "standard") = mainHandler.post {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        val uri = Uri.parse(location)
        sourceUri = uri
        val scheme = when (uri.scheme) {
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> "other"
        }
        val local = uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "::1"
        diagnostic("[VIDEO] $scheme local=$local origin=${origin(uri)} resource=${resourceKind(uri)} start=${startPositionSeconds}s")
        val remote = origin(uri) == "remote" && uri.scheme in listOf("http", "https")
        val profile = if (remote) requestedProfile else "standard"
        val format = if (remote) requestedFormat else "standard"
        autoProfile = profile == "auto"
        autoFormat = format == "auto"
        val agent = profileAgent(profile)
        diagnostic("[COMPAT] profile=$profile format=$format remote=$remote")
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val builder = ExoPlayer.Builder(context, renderers)
        if (agent != null || autoProfile) {
            val http = DefaultHttpDataSource.Factory().setUserAgent(agent)
            playbackHttpFactory = http
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        }
        val p = builder.build().also {
            it.addListener(_listener)
            it.addAnalyticsListener(loadDiagnostics)
            pendingSurface?.let { s -> it.setVideoSurface(s) }
            it.volume = clientGain
        }
        player = p
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("it")
            .build()
        val startMs = (startPositionSeconds * 1000).toLong().coerceAtLeast(0)
        initialStartMs = startMs
        val item = MediaItem.Builder().setUri(uri)
        p.playWhenReady = true
        onPlaybackInfo?.invoke(PlaybackSnapshot(startPositionSeconds, 0f, 0f, false, true))
        fun start(mime: String?) {
            if (player !== p) return
            if (mime != null) item.setMimeType(mime)
            p.setMediaItem(item.build(), startMs)
            p.prepare()
            mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
        }
        when (format) {
            "hls" -> start(MimeTypes.APPLICATION_M3U8)
            "dash" -> start(MimeTypes.APPLICATION_MPD)
            "detect" -> {
                if (resourceKind(uri) in setOf("HLS playlist", "DASH manifest", "MP4")) {
                    start(null)
                } else {
                    val id = sessionId
                    probeTask = probeExecutor.submit {
                        val (mime, details) = detectMime(uri, agent)
                        mainHandler.post {
                            if (sessionId == id && player === p) {
                                probeTask = null
                                diagnostic("[PROBE] $details")
                                start(mime)
                            }
                        }
                    }
                }
            }
            else -> start(null)
        }
    }

    fun scrub(positionSeconds: Float) = mainHandler.post {
        val target = (positionSeconds * 1000).toLong().coerceAtLeast(0L)
        if (recovery != null && recoveryTargetMs != C.TIME_UNSET) {
            recoveryTargetMs = target
            recoveryPositionMs = target
        }
        player?.seekTo(target)
    }

    // coalesces the seek spam from drag-seeking
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setScrubbing(enabled: Boolean) = mainHandler.post {
        player?.isScrubbingModeEnabled = enabled
    }

    fun setRate(rate: Float) = mainHandler.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = mainHandler.post {
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    fun setSpeed(speed: Float) = mainHandler.post {
        player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    // linear, unity unless sender volume isn't synced to receiver
    fun setClientGain(gain: Float) = mainHandler.post {
        clientGain = gain
        player?.volume = gain
    }

    fun setSkipSilence(enabled: Boolean) = mainHandler.post {
        player?.skipSilenceEnabled = enabled
    }

    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = mainHandler.post {
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = mainHandler.post {
        if (pendingSurface !== surface) return@post
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    fun stop() = mainHandler.post { _stopInternal(reportStopped = true) }

    private fun _stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        sessionId++
        probeTask?.cancel(true)
        probeTask = null
        recovery?.let { mainHandler.removeCallbacks(it) }
        recovery = null
        recoveryAttempts = 0
        recoveryPositionMs = 0L
        recoveryDurationMs = 0L
        wasReady = false
        lastKnownLive = false
        recoveryInProgress = false
        recoveryTargetMs = C.TIME_UNSET
        lastFailedResource = "unknown"
        lastHttpDetails = "origin=unknown"
        sourceUri = null
        autoProfile = false
        autoFormat = false
        profileSwitched = false
        formatSwitched = false
        observedManifestMime = null
        playbackHttpFactory = null
        initialStartMs = 0L
        autoSwitchPending = false
        player?.let {
            it.removeListener(_listener)
            it.removeAnalyticsListener(loadDiagnostics)
            it.release()
        }
        player = null
        _subtitleTracks.value = emptyList()
        _subtitleCues.value = emptyList()
        firstCueLogged = false
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val recovering = recoveryInProgress
        val durationMs = if (recovering && recoveryDurationMs > 0) recoveryDurationMs else p.duration
        val position = (if (recovering) recoveryPositionMs else p.currentPosition) / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (recovering) wasReady else if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = recovering || p.playbackState == Player.STATE_BUFFERING,
            )
        )
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
