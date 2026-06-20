package com.example.videoanalyzer.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.videoanalyzer.util.VideoUtils
import com.example.videoanalyzer.util.VideoUtils.MaxResolution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onResetSetup: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.uploadProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var resDropdownExpanded by remember { mutableStateOf(false) }
    var chatActionsMenuExpanded by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) { /* some providers don't grant persistable */ }
            val name = queryDisplayName(context, uri) ?: "video"
            vm.onVideoPicked(uri, name)
        }
    }

    val listState = rememberLazyListState()
    // Auto-scroll to the newest message when chat history grows.
    LaunchedEffect(state.chatHistory.size, state.isSending) {
        if (state.chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.chatHistory.size - 1)
        }
    }

    // ---- Size confirmation dialog (only fires on the first message) ----
    if (state.pendingFirstSendText != null) {
        val sizeBytes = state.pendingSizeBytes ?: 0L
        val pretty = VideoUtils.formatBytes(sizeBytes)
        AlertDialog(
            onDismissRequest = { vm.cancelSend() },
            title = { Text("Large upload") },
            text = {
                Column {
                    Text(
                        "This video is $pretty. Uploading will send the full file " +
                            "to your configured API provider over the network. Follow-up " +
                            "questions after this won't re-upload the video.",
                    )
                    if (state.maxResolution != MaxResolution.OFF) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Local downscale is enabled (${state.maxResolution.label}), so the actual upload may be smaller after re-encoding.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmSend() }) { Text("Send anyway") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelSend() }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Analyzer") },
                actions = {
                    if (state.chatHistory.isNotEmpty()) {
                        IconButton(onClick = vm::clearChat) {
                            Icon(Icons.Filled.ClearAll, contentDescription = "Clear chat")
                        }
                    }
                    IconButton(onClick = vm::refreshModels) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh models")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = {
            ChatInputBar(
                pendingMessage = state.pendingMessage,
                isSending = state.isSending,
                canSend = state.videoUri != null && state.selectedModel.isNotBlank() && !state.isSending,
                onMessageChange = vm::onPendingMessageChange,
                onSend = { vm.onSendClicked() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- Compact video + model card ----
            VideoInfoCard(
                state = state,
                modelDropdownExpanded = modelDropdownExpanded,
                onModelDropdownToggle = { modelDropdownExpanded = !modelDropdownExpanded },
                onModelSelected = {
                    vm.onModelSelected(it)
                    modelDropdownExpanded = false
                },
                resDropdownExpanded = resDropdownExpanded,
                onResDropdownToggle = { resDropdownExpanded = !resDropdownExpanded },
                onResSelected = {
                    vm.onMaxResolutionChange(it)
                    resDropdownExpanded = false
                },
                onChangeVideo = {
                    pickVideo.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.VideoOnly,
                        ),
                    )
                },
                onClearVideo = { vm.onVideoPicked(null, null) },
            )

            // ---- Error banner ----
            if (state.error != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = state.error!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- Upload progress (first message only) ----
            if (progress.isActive && progress.totalBytes > 0L) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Uploading video… ${VideoUtils.formatBytes(progress.sentBytes)} / ${VideoUtils.formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ---- Chat history ----
            if (state.chatHistory.isEmpty() && !state.isSending) {
                EmptyChatHint()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = 8.dp,
                    ),
                ) {
                    items(state.chatHistory, key = { it.timestamp }) { item ->
                        ChatBubble(item)
                    }
                    if (state.isSending) {
                        item(key = "typing") { TypingIndicator() }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Sub-components
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoInfoCard(
    state: HomeViewModel.UiState,
    modelDropdownExpanded: Boolean,
    onModelDropdownToggle: () -> Unit,
    onModelSelected: (String) -> Unit,
    resDropdownExpanded: Boolean,
    onResDropdownToggle: () -> Unit,
    onResSelected: (MaxResolution) -> Unit,
    onChangeVideo: () -> Unit,
    onClearVideo: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Model picker (compact)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.SmartToy, contentDescription = null)
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { onModelDropdownToggle() },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = state.selectedModel.ifBlank { "No model selected" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { onModelDropdownToggle() },
                    ) {
                        if (state.models.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models yet — refresh from Settings") },
                                onClick = { onModelDropdownToggle() },
                            )
                        } else {
                            state.models.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = { onModelSelected(model) },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Video row
            if (state.videoUri == null) {
                OutlinedButton(
                    onClick = onChangeVideo,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick a video to start chatting")
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.videoDisplayName ?: "video",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        state.videoSizeBytes?.let { size ->
                            Text(
                                VideoUtils.formatBytes(size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    OutlinedButton(onClick = onChangeVideo) { Text("Change") }
                    IconButton(onClick = onClearVideo) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear video")
                    }
                }

                // Resolution picker
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.SwapVert, contentDescription = null)
                    ExposedDropdownMenuBox(
                        expanded = resDropdownExpanded,
                        onExpandedChange = { onResDropdownToggle() },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = state.maxResolution.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Max upload res") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = resDropdownExpanded,
                            onDismissRequest = { onResDropdownToggle() },
                        ) {
                            MaxResolution.values().forEach { res ->
                                DropdownMenuItem(
                                    text = { Text(res.label) },
                                    onClick = { onResSelected(res) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    pendingMessage: String,
    isSending: Boolean,
    canSend: Boolean,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pendingMessage,
                    onValueChange = onMessageChange,
                    placeholder = { Text("Ask a question about the video…") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 140.dp),
                    maxLines = 6,
                    enabled = !isSending,
                    keyboardOptions = KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (canSend) onSend() },
                    ),
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(
                    onClick = onSend,
                    enabled = canSend && pendingMessage.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (canSend && pendingMessage.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend && pendingMessage.isNotBlank())
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    val isUser = item.role == ChatRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val bubbleShape = if (isUser) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 4.dp,
            bottomEnd = 18.dp,
            bottomStart = 18.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 18.dp,
            bottomEnd = 18.dp,
            bottomStart = 18.dp,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatHint() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "No messages yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Pick a video above, then type a question. " +
                    "The video uploads once with your first message — " +
                    "after that you can keep chatting without re-uploading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================================
// Helpers
// ============================================================================

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
    } catch (_: Throwable) { null }
}