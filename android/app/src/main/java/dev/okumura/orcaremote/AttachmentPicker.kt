package dev.okumura.orcaremote

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AttachmentPicker(draft: ChatDraft, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val onResult: (List<Uri>) -> Unit = { uris ->
        if (enabled) scope.launch { draft.add(resolver, uris) }
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(draft.limits.count), onResult)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onResult)
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled && !draft.reading) {
            OrcaIcon(R.drawable.ic_attach, "ファイルや画像を添付")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("画像を選ぶ") }, onClick = {
                menu = false
                images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            DropdownMenuItem(text = { Text("ファイルを選ぶ") }, onClick = {
                menu = false
                files.launch(arrayOf("*/*"))
            })
        }
    }
}

@Composable
internal fun AttachmentPreview(draft: ChatDraft, enabled: Boolean) {
    val resolver = LocalContext.current.contentResolver
    if (draft.attachments.isNotEmpty()) Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (file in draft.attachments) key(file.uri) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bitmap by produceState<Bitmap?>(null, file.uri) {
                        if (file.image) value = thumbnail(resolver, file)
                    }
                    val preview = bitmap
                    if (preview == null) OrcaIcon(R.drawable.ic_file)
                    else Image(preview.asImageBitmap(), null, Modifier.size(40.dp), contentScale = ContentScale.Crop)
                    Column(Modifier.widthIn(max = 132.dp).padding(start = 6.dp)) {
                        Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fileSize(file.size), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { draft.remove(file) }, enabled = enabled && !draft.reading) {
                        OrcaIcon(R.drawable.ic_close, "${file.name} を外す")
                    }
                }
            }
        }
    }
    draft.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
