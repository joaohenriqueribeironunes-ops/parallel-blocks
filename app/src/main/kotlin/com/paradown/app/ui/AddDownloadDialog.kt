package com.paradown.app.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paradown.app.R
import com.paradown.app.util.UrlTools

private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

@Composable
fun AddDownloadDialog(
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onConfirm: (url: String, sha256: String?) -> Unit,
) {
    val context = LocalContext.current
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var sha by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun pasteLink() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        val link = UrlTools.extractFirstUrl(text)
        if (link == null) {
            error = context.getString(R.string.add_paste_empty)
        } else {
            url = link
            error = null
        }
    }

    fun confirm() {
        val normalized = UrlTools.normalize(url)
        if (normalized == null) {
            error = context.getString(R.string.add_invalid_link)
            return
        }
        val trimmedSha = sha.trim()
        if (trimmedSha.isNotEmpty() && !sha256Pattern.matches(trimmedSha)) {
            error = context.getString(R.string.add_sha_invalid)
            return
        }
        error = null
        onConfirm(normalized, trimmedSha.ifEmpty { null }?.lowercase())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.add_link_label)) },
                    placeholder = { Text(stringResource(R.string.add_link_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { pasteLink() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_paste))
                }
                OutlinedTextField(
                    value = sha,
                    onValueChange = {
                        sha = it.trim()
                        error = null
                    },
                    label = { Text(stringResource(R.string.add_sha_label)) },
                    supportingText = { Text(stringResource(R.string.add_sha_support)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }) {
                Text(stringResource(R.string.add_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.add_cancel))
            }
        },
    )
}
