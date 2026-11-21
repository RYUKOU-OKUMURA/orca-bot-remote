package dev.okumura.orcaremote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

internal fun appendSpeech(draft: String, result: String?): String {
    val text = result?.trim().orEmpty()
    return when {
        text.isEmpty() -> draft
        draft.isEmpty() || draft.last().isWhitespace() -> draft + text
        else -> draft + "\n" + text
    }
}

@Composable
internal fun VoiceInput(sender: SendModel<*>, enabled: Boolean, autoStart: Boolean = false) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && !sender.sending && sender.result == null) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            sender.draft = appendSpeech(sender.draft, words)
        }
    }
    fun launch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            sender.chat?.notice = "この端末では音声入力を利用できません。キーボードで入力してください"
        } catch (_: SecurityException) {
            sender.chat?.notice = "音声入力を起動できません。キーボードで入力してください"
        }
    }
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart, enabled) {
        if (autoStart && enabled && !launched) {
            launched = true
            launch()
        }
    }
    IconButton(onClick = ::launch, enabled = enabled) { OrcaIcon(R.drawable.ic_mic, "音声入力") }
}
