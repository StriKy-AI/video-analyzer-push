package com.example.videoanalyzer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.videoanalyzer.util.VideoUtils.MaxResolution
import com.example.videoanalyzer.util.VideoUtils.WarnThreshold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onWipeAndGoToSetup: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var dropdownExpanded by remember { mutableStateOf(false) }
    var resExpanded by remember { mutableStateOf(false) }
    var warnExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Provider config ----
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = vm::onBaseUrlChange,
                label = { Text("Base URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::onApiKeyChange,
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = vm::refreshModels,
                    enabled = !state.isRefreshing && state.baseUrl.isNotBlank() && state.apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Refreshing…")
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Re-fetch models")
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = !dropdownExpanded },
            ) {
                OutlinedTextField(
                    value = state.selectedModel.ifBlank { "Select a model" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    if (state.models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models loaded yet") },
                            onClick = { dropdownExpanded = false },
                        )
                    } else {
                        state.models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    vm.onModelSelected(m)
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // ---- Upload behavior section ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Upload behavior",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    // Max resolution
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.HighQuality, contentDescription = null)
                        Text("Max upload resolution", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Re-encode the video locally before upload. Smaller = faster + cheaper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExposedDropdownMenuBox(
                        expanded = resExpanded,
                        onExpandedChange = { resExpanded = !resExpanded },
                    ) {
                        OutlinedTextField(
                            value = state.maxResolution.label,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = resExpanded,
                            onDismissRequest = { resExpanded = false },
                        ) {
                            MaxResolution.values().forEach { r ->
                                DropdownMenuItem(
                                    text = { Text(r.label) },
                                    onClick = {
                                        vm.onMaxResolutionChange(r)
                                        resExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider() // resolved import below

                    // Warn threshold
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null)
                        Text("Warn before uploading", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Show a confirmation dialog when the picked video is bigger than the threshold.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExposedDropdownMenuBox(
                        expanded = warnExpanded,
                        onExpandedChange = { warnExpanded = !warnExpanded },
                    ) {
                        OutlinedTextField(
                            value = state.warnThreshold.label,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = warnExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = warnExpanded,
                            onDismissRequest = { warnExpanded = false },
                        ) {
                            WarnThreshold.values().forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    onClick = {
                                        vm.onWarnThresholdChange(t)
                                        warnExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // Gzip toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Compress, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Gzip request body", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Compress the upload. Some providers don't accept Content-Encoding: gzip — turn off if requests fail with 400.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.gzipEnabled,
                            onCheckedChange = vm::onGzipEnabledChange,
                        )
                    }
                }
            }

            Button(
                onClick = vm::save,
                enabled = state.baseUrl.isNotBlank() && state.apiKey.isNotBlank() && state.selectedModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save provider changes")
            }

            if (state.error != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        state.error!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (state.notice != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        state.notice!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(Modifier.size(8.dp))

            // ---- Danger zone ----
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Danger zone",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Wipe the saved base URL, API key, selected model, and cached model list. You'll be sent back to setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    OutlinedButton(
                        onClick = { vm.wipeAll(onWipeAndGoToSetup) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Wipe everything")
                    }
                }
            }
        }
    }
}