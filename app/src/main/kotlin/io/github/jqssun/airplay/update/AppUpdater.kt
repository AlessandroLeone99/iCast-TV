package io.github.jqssun.airplay.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class AppRelease(val version: String, val url: String, val size: Long, val digest: String?)
data class UpdateState(
    val checking: Boolean = false,
    val release: AppRelease? = null,
    val progress: Int? = null,
    val ready: Boolean = false,
    val message: String? = null,
)

@Suppress("DEPRECATION")
class AppUpdater(private val app: Application, private val scope: CoroutineScope) {
    private val pm = app.packageManager
    private val current = pm.getPackageInfo(app.packageName, 0)
    val installedVersion: String = current.versionName ?: "?"
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private val apk = File(app.cacheDir, "icast-update.apk")
    private val partial = File(app.cacheDir, "icast-update.part")

    fun check(automatic: Boolean = false) {
        if (job?.isActive == true) return
        if (automatic && _state.value.release != null) return
        _state.value = UpdateState(checking = true)
        job = scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { findRelease() }
                _state.value = UpdateState(release = release,
                    message = if (release == null) "Nessun aggiornamento disponibile." else "Nuova versione disponibile.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState(message = if (automatic) null else "Controllo non riuscito. Riprova.")
            }
        }
    }

    fun download() {
        val release = _state.value.release ?: return
        if (job?.isActive == true) return
        _state.value = UpdateState(release = release, progress = 0)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    apk.delete()
                    partial.delete()
                    val connection = connect(release.url, ASSET_HOSTS)
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var lastProgress = -1
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= release.size && total <= MAX_APK)
                                    digest.update(buffer, 0, n)
                                    output.write(buffer, 0, n)
                                    val percent = (total * 100 / release.size).toInt()
                                    if (percent != lastProgress) {
                                        lastProgress = percent
                                        _state.value = UpdateState(release = release, progress = percent)
                                    }
                                }
                            }
                        }
                        require(total == release.size) { "Incomplete download" }
                        if (release.digest != null) {
                            require(hex(digest.digest()) == release.digest) { "SHA-256 mismatch" }
                        }
                        verifyApk(partial)
                        check(partial.renameTo(apk))
                    } finally {
                        connection.disconnect()
                    }
                }
                _state.value = UpdateState(release = release, ready = true, message = "APK verificato. Conferma l'installazione.")
            } catch (e: CancellationException) {
                partial.delete()
                throw e
            } catch (e: Exception) {
                partial.delete()
                apk.delete()
                _state.value = UpdateState(release = release, message = "Download o verifica non riusciti. Riprova.")
            }
        }
    }

    fun cancel() {
        if (job?.isActive != true) return
        job?.cancel()
        _state.value = UpdateState(release = _state.value.release, message = "Operazione annullata.")
    }

    fun install(context: Context) {
        if (!_state.value.ready || !apk.isFile) return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + app.packageName)))
                _state.value = _state.value.copy(message = "Autorizza l'installazione per iCast TV, poi premi Installa.")
                return
            }
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("iCast update", uri)
            })
        } catch (e: Exception) {
            _state.value = _state.value.copy(message = "Installer non disponibile. Controlla le impostazioni di Fire OS.")
        }
    }

    private fun findRelease(): AppRelease? {
        val installed = versionParts(installedVersion) ?: return null
        val connection = connect(API, setOf("api.github.com"))
        val text = try {
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val out = StringBuilder()
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    require(out.length + n <= 2_000_000)
                    out.append(buffer, 0, n)
                }
                out.toString()
            }
        } finally { connection.disconnect() }
        val releases = JSONArray(text)
        var best: AppRelease? = null
        var bestVersion = installed
        for (i in 0 until releases.length()) {
            val item = releases.getJSONObject(i)
            if (item.optBoolean("draft")) continue
            val tag = item.optString("tag_name")
            if (!tag.startsWith("v")) continue
            val version = tag.removePrefix("v")
            val parts = versionParts(version) ?: continue
            if (compare(parts, bestVersion) <= 0) continue
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.optString("name") != "iCast-TV.apk" || asset.optString("state") != "uploaded") continue
                val url = asset.optString("browser_download_url")
                if (url != "$DOWNLOAD/$tag/iCast-TV.apk") continue
                val size = asset.optLong("size")
                if (size !in 1L..MAX_APK) continue
                val rawDigest = asset.optString("digest", "")
                val digest = rawDigest.removePrefix("sha256:").lowercase()
                    .takeIf { rawDigest.startsWith("sha256:") && it.matches(Regex("[0-9a-f]{64}")) }
                best = AppRelease(version, url, size, digest)
                bestVersion = parts
                break
            }
        }
        return best
    }

    private fun verifyApk(file: File) {
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("Invalid APK")
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
        require(candidate.packageName == app.packageName)
        require(code(candidate) > code(installed))
        val incoming = candidate.signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        val existing = installed.signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        require(incoming == listOf(CERT) && existing == listOf(CERT)) { "Signing certificate mismatch" }
    }

    private fun code(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun connect(raw: String, hosts: Set<String>): HttpURLConnection {
        var url = URL(raw)
        repeat(6) {
            require(url.protocol == "https" && url.host.lowercase() in hosts && url.userInfo == null)
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("User-Agent", "iCast-TV-updater")
            c.setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            try {
                val status = c.responseCode
                if (status == 200) return c
                if (status !in listOf(301, 302, 303, 307, 308)) error("HTTP $status")
                url = URL(url, c.getHeaderField("Location") ?: error("Missing redirect"))
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
            c.disconnect()
        }
        error("Too many redirects")
    }

    private fun versionParts(value: String): List<Int>? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)-icast\.(\d+)""").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val API = "https://api.github.com/repos/AlessandroLeone99/iCast-TV/releases?per_page=30"
        private const val DOWNLOAD = "https://github.com/AlessandroLeone99/iCast-TV/releases/download"
        private const val MAX_APK = 100L * 1024 * 1024
        private const val CERT = "0d2358cf22e2af9f42eb8cc3c7d61c650937bda9e049edf56f42cc92def1e707"
        private val ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }
}
