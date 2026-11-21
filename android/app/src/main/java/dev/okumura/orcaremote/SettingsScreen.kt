package dev.okumura.orcaremote

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

internal class SettingsModel(connection: Connection?) : ViewModel() {
    var url by mutableStateOf(connection?.url?.toString().orEmpty())
    val token = TextFieldState(initialText = connection?.token.orEmpty())

    var checking by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var verified by mutableStateOf<Connection?>(null)
        private set

    fun check() {
        val candidate = Connection.parse(url, token.text.toString())
        if (candidate == null) {
            errorText = "サーバー URL とトークンを確認してください"
            return
        }
        viewModelScope.launch {
            checking = true
            errorText = null
            try {
                OrcaApi(candidate).bots()
                verified = candidate
            } catch (failure: ApiFailure) {
                errorText = failure.error.text
            } finally {
                checking = false
            }
        }
    }

    fun permissionDenied() {
        errorText = "Mac mini への接続にはローカルネットワークの許可が必要です"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    connection: Connection?,
    back: (() -> Unit)?,
    notificationsEnabled: Boolean,
    onNotificationsChanged: (Boolean) -> Unit,
    onSaved: (Connection) -> Unit,
) {
    val context = LocalContext.current
    val model: SettingsModel = viewModel { SettingsModel(connection) }
    val checkConnection = withLocalNetworkPermission(model::permissionDenied, model::check)
    LaunchedEffect(model.verified) { model.verified?.let(onSaved) }

    Page("設定", back = back) { modifier ->
        Column(
            modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Mac mini への接続", style = MaterialTheme.typography.headlineSmall)
            Text("Tailscale をオンにして、Mac mini のアドレスとトークンを入力してください。")
            OutlinedTextField(
                value = model.url,
                onValueChange = { model.url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("サーバー URL") },
                placeholder = { Text("http://mac-mini:8787") },
                singleLine = true,
                enabled = !model.checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedSecureTextField(
                state = model.token,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("トークン") },
                textObfuscationMode = TextObfuscationMode.Hidden,
                enabled = !model.checking,
            )
            Button(
                onClick = checkConnection,
                enabled = !model.checking && model.url.isNotBlank() && model.token.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (model.checking) "接続を確認中…" else "接続を確認して保存")
            }
            if (model.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(notificationsEnabled, role = Role.Switch, onValueChange = onNotificationsChanged),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("通知")
                Switch(checked = notificationsEnabled, onCheckedChange = null)
            }
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }) {
                Text("通知を許可する")
            }
        }
    }
}

@Composable
internal fun withLocalNetworkPermission(
    onDenied: () -> Unit,
    onGranted: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) onGranted() else onDenied()
    }
    return {
        if (Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(permission)
        } else {
            onGranted()
        }
    }
}
