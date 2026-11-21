package dev.okumura.orcaremote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
internal fun AppUpdateSection(connection: Connection?) {
    val context = LocalContext.current
    val api = remember(connection) { connection?.let { OrcaApi(it) } }
    val scope = rememberCoroutineScope()
    var release by remember(api) { mutableStateOf<AppRelease?>(null) }
    var latestStatus by remember(api) { mutableStateOf<String?>(null) }
    var installStatus by remember(api) { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloaded by remember { mutableLongStateOf(0) }

    LaunchedEffect(api) {
        if (api != null) {
            latestStatus = "最新の版を確認中…"
            try {
                release = api.latestApp()
                latestStatus = if (release == null) "配信中の版がありません" else null
            } catch (_: ApiFailure) {
                latestStatus = "最新の版を確認できませんでした"
            }
        }
    }

    val latest = release
    val canUpdate = latest != null && isNewerRelease(latest, BuildConfig.VERSION_CODE.toLong())
    Text("アプリの更新", style = MaterialTheme.typography.headlineSmall)
    Text("いまの版 ${BuildConfig.VERSION_NAME}")
    latestStatus?.let { Text(it) }
    if (latest != null) {
        Text("最新の版 ${latest.versionName}")
        if (!canUpdate) Text("最新です")
    }
    OrcaOutlinedButton(enabled = canUpdate && !downloading, onClick = {
        if (api == null || latest == null || downloading) return@OrcaOutlinedButton
        downloading = true
        downloaded = 0
        installStatus = null
        scope.launch {
            try {
                if (!prepareAppInstall(context, api, latest) { downloaded = it }) {
                    installStatus = "「この提供元のアプリを許可」をオンにして、戻ったらもう一度「更新」を押してください"
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null)))
                }
            } catch (_: InvalidAppDownload) {
                installStatus = "ダウンロードしたファイルを確認できませんでした"
            } catch (_: IOException) {
                installStatus = "更新ファイルを取得または準備できませんでした"
            } catch (_: SecurityException) {
                installStatus = "更新のインストールを開始できませんでした"
            } catch (_: android.content.ActivityNotFoundException) {
                installStatus = "インストールの許可画面を開けませんでした"
            } finally {
                downloading = false
            }
        }
    }) {
        Text("更新")
    }
    if (downloading && latest != null) {
        Text("ダウンロード中… $downloaded / ${latest.size} バイト")
        LinearProgressIndicator(progress = { downloaded.toFloat() / latest.size }, modifier = Modifier.fillMaxWidth())
    }
    installStatus?.let { Text(it) }
}

private suspend fun prepareAppInstall(
    context: Context,
    api: OrcaApi,
    release: AppRelease,
    onProgress: (Long) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "update")
    if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create update directory")
    val apk = File.createTempFile("orca-", ".apk", dir)
    try {
        api.downloadApp(release, apk, onProgress)
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(apk.path, 0)
        if (!matchesAppArchive(release, context.packageName, archive?.packageName, archive?.longVersionCode)) {
            throw InvalidAppDownload()
        }
        if (!manager.canRequestPackageInstalls()) return@withContext false

        val installer = manager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(release.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, release.size).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                currentCoroutineContext().ensureActive()
                val callback = PendingIntent.getBroadcast(context, id,
                    Intent(context, AppInstallReceiver::class.java), PendingIntent.FLAG_MUTABLE)
                session.commit(callback.intentSender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(id)
        }
        true
    } finally {
        apk.delete()
    }
}

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> return
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    try {
                        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (_: android.content.ActivityNotFoundException) {
                    } catch (_: SecurityException) {
                    }
                }
            }
        }
        Toast.makeText(context, "更新をインストールできませんでした", Toast.LENGTH_LONG).show()
    }
}
