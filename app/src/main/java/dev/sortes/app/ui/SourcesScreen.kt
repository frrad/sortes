package dev.sortes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sortes.app.alphaxiv.AlphaxivFolderLink
import dev.sortes.app.data.FolderSource
import java.text.DateFormat
import java.util.Date

@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourcesViewModel = viewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    SourcesList(
        sources = sources,
        syncing = syncing,
        onAdd = viewModel::addAndSync,
        onSync = viewModel::sync,
        onSyncAll = viewModel::syncAll,
        onRemove = viewModel::remove,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun SourcesList(
    sources: List<FolderSource>,
    syncing: Set<Long>,
    modifier: Modifier = Modifier,
    onAdd: (folderId: String) -> Unit = {},
    onSync: (Long) -> Unit = {},
    onSyncAll: () -> Unit = {},
    onRemove: (Long) -> Unit = {},
    onBack: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text(
                    text = "Sources",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSyncAll, enabled = sources.any { it.id !in syncing }) {
                    Text("Sync all")
                }
            }
        }
        item { AddSourceField(onAdd) }
        if (sources.isEmpty()) {
            item {
                Text(
                    text = "No sources yet. Paste an alphaXiv shared-folder link to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(sources, key = { it.id }) { source ->
            SourceCard(
                source = source,
                isSyncing = source.id in syncing,
                onSync = { onSync(source.id) },
                onRemove = { onRemove(source.id) },
            )
        }
    }
}

@Composable
private fun AddSourceField(onAdd: (folderId: String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val submit = {
        val folderId = AlphaxivFolderLink.parse(text)
        if (folderId == null) {
            invalid = true
        } else {
            onAdd(folderId)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            label = { Text("alphaXiv folder link") },
            isError = invalid,
            supportingText = if (invalid) {
                { Text("Not an alphaXiv shared-folder link") }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) submit() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = submit, enabled = text.isNotBlank()) { Text("Add") }
    }
}

@Composable
private fun SourceCard(source: FolderSource, isSyncing: Boolean, onSync: () -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = source.name ?: "alphaXiv folder", style = MaterialTheme.typography.titleMedium)
            Text(
                text = AlphaxivFolderLink.url(source.folderId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = when {
                isSyncing -> "Syncing…"
                source.lastSyncedAt == null -> "Never synced"
                else -> "${formatTime(source.lastSyncedAt)}: ${source.lastResult.orEmpty()}"
            }
            Text(text = status, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onSync, enabled = !isSyncing) { Text("Sync") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
