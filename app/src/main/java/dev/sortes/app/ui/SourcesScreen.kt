package dev.sortes.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sortes.app.alphaxiv.AlphaxivFolderLink
import dev.sortes.app.data.FolderSource
import dev.sortes.app.ui.theme.InkBorder
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
    val error by viewModel.error.collectAsStateWithLifecycle()
    SourcesList(
        sources = sources,
        syncing = syncing,
        error = error,
        onDismissError = viewModel::dismissError,
        onAdd = viewModel::addAndSync,
        onSync = viewModel::sync,
        onSyncAll = viewModel::syncAll,
        onRemove = viewModel::remove,
        onBack = onBack,
        onFailuresSeen = viewModel::markFailuresSeen,
        modifier = modifier,
    )
}

@Composable
internal fun SourcesList(
    sources: List<FolderSource>,
    syncing: Set<Long>,
    modifier: Modifier = Modifier,
    error: String? = null,
    onDismissError: () -> Unit = {},
    onAdd: (folderId: String) -> Unit = {},
    onSync: (Long) -> Unit = {},
    onSyncAll: () -> Unit = {},
    onRemove: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    onFailuresSeen: () -> Unit = {},
) {
    // Failures show here, so the paper list needn't point to them: neither those already
    // reported when this screen opens nor those that happen while it's open.
    val currentOnFailuresSeen by rememberUpdatedState(onFailuresSeen)
    DisposableEffect(Unit) {
        currentOnFailuresSeen()
        onDispose { currentOnFailuresSeen() }
    }
    Column(modifier = modifier.fillMaxSize()) {
        InkTopBar(title = "Folder sources", navigationIcon = { BackArrow(onBack) }) {
            TextButton(onClick = onSyncAll, enabled = sources.any { it.id !in syncing }) {
                Text("Sync all")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AddSourceField(onAdd) }
            if (error != null) {
                item(key = "error") {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onDismissError)
                            .padding(vertical = 4.dp),
                    )
                }
            }
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

internal const val SOURCE_CARD_TAG = "sourceCard"

@Composable
private fun SourceCard(source: FolderSource, isSyncing: Boolean, onSync: () -> Unit, onRemove: () -> Unit) {
    OutlinedCard(
        border = InkBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SOURCE_CARD_TAG),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = source.name ?: "alphaXiv folder", style = MaterialTheme.typography.titleMedium)
            Text(
                text = AlphaxivFolderLink.url(source.folderId),
                style = MaterialTheme.typography.labelSmall,
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
