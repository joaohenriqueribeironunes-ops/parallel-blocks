package com.paradown.app.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.paradown.app.ParaDownApplication
import com.paradown.app.R
import com.paradown.app.ui.theme.ParaDownTheme
import com.paradown.app.util.FileNames
import com.paradown.app.util.UrlTools
import kotlinx.coroutines.launch

/**
 * Tela curta usada quando outro app envia um link via ACTION_VIEW ou ACTION_SEND.
 * Confirma o download e delega a fila ao DownloadCoordinator.
 */
class CaptureActivity : ComponentActivity() {

    private val isLoading = mutableStateOf(false)
    private val errorMessage = mutableStateOf<String?>(null)

    private var captureUrl: String? = null
    private var captureMime: String? = null
    private var captureFileName: String = "download"

    private val coordinator
        get() = (application as ParaDownApplication).coordinator

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startDownload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        captureUrl = savedInstanceState?.getString(STATE_URL) ?: resolveUrl(intent)
        val resolved = captureUrl
        if (resolved == null) {
            Toast.makeText(this, R.string.capture_invalid, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        captureMime = savedInstanceState?.getString(STATE_MIME) ?: mimeFromIntent(intent)
        captureFileName = savedInstanceState?.getString(STATE_FILE_NAME)
            ?: FileNames.defaultNameFor(
                UrlTools.fileNameFromUrl(resolved),
                captureMime,
            )

        setContent {
            ParaDownTheme {
                CaptureScreen(
                    url = resolved,
                    fileName = captureFileName,
                    loading = isLoading.value,
                    error = errorMessage.value,
                    onDownload = ::requestDownload,
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, captureUrl)
        outState.putString(STATE_MIME, captureMime)
        outState.putString(STATE_FILE_NAME, captureFileName)
    }

    private fun requestDownload() {
        if (isLoading.value || isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startDownload()
        }
    }

    private fun startDownload() {
        val url = captureUrl ?: return
        if (isLoading.value || isFinishing) return
        isLoading.value = true
        errorMessage.value = null
        lifecycleScope.launch {
            try {
                coordinator.enqueue(
                    url = url,
                    mimeType = captureMime,
                )
                Toast.makeText(
                    this@CaptureActivity,
                    R.string.capture_started,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } catch (e: Exception) {
                isLoading.value = false
                errorMessage.value = getString(
                    R.string.capture_error,
                    e.message ?: "erro desconhecido",
                )
            }
        }
    }

    private fun resolveUrl(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        return UrlTools.extractFirstUrl(raw)
    }

    private fun mimeFromIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.type?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val STATE_URL = "capture_url"
        private const val STATE_MIME = "capture_mime"
        private const val STATE_FILE_NAME = "capture_file_name"
    }
}

@Composable
private fun CaptureScreen(
    url: String,
    fileName: String,
    loading: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "↓",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.capture_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.capture_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.capture_file_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (error != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Text(
                                    text = error,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onCancel,
                                enabled = !loading,
                            ) {
                                Text(stringResource(R.string.capture_cancel_button))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onDownload,
                                enabled = !loading,
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = LocalContentColor.current,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.capture_adding))
                                } else {
                                    Text(stringResource(R.string.capture_download_button))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
