package dev.sortes.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sortes.app.data.Paper
import dev.sortes.app.ui.theme.SortesTheme

@Composable
fun PapersScreen(
    onPaperClick: (Paper) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PapersViewModel = viewModel(),
) {
    val papers by viewModel.papers.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPdf(uri)
    }

    Box(modifier = modifier.fillMaxSize()) {
        PapersList(
            papers = papers,
            onPaperClick = onPaperClick,
            importStatus = importStatus,
            onDismissImportStatus = viewModel::dismissImportStatus,
        )
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add paper")
        }
    }

    if (showAddDialog) {
        AddPaperDialog(
            onImportArxiv = {
                showAddDialog = false
                viewModel.importArxiv(it)
            },
            onPickPdf = {
                showAddDialog = false
                pickPdf.launch(arrayOf("application/pdf"))
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
internal fun AddPaperDialog(
    onImportArxiv: (String) -> Unit,
    onPickPdf: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add paper") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("arXiv ID or URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onImportArxiv(text) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onImportArxiv(text) }, enabled = text.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPickPdf) { Text("Pick PDF…") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun PapersList(
    papers: List<Paper>,
    modifier: Modifier = Modifier,
    onPaperClick: (Paper) -> Unit = {},
    importStatus: ImportStatus? = null,
    onDismissImportStatus: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Bottom padding keeps the last card clear of the add button.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Papers",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (importStatus != null) {
            item(key = "import-status") {
                ImportStatusLine(importStatus, onDismiss = onDismissImportStatus)
            }
        }
        if (papers.isEmpty()) {
            item {
                Text(
                    text = "No papers yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(papers, key = { it.id }) { paper ->
            PaperCard(paper, onClick = { onPaperClick(paper) })
        }
    }
}

@Composable
private fun ImportStatusLine(status: ImportStatus, onDismiss: () -> Unit) {
    val (text, color) = when (status) {
        ImportStatus.Running -> "Importing…" to MaterialTheme.colorScheme.onSurfaceVariant
        is ImportStatus.Done -> "Added: ${status.title}" to MaterialTheme.colorScheme.primary
        is ImportStatus.Failed -> "Import failed: ${status.message}" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = status != ImportStatus.Running, onClick = onDismiss)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun PaperCard(paper: Paper, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = paper.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = paper.authors.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            val meta = listOfNotNull(paper.venue, paper.year?.toString())
                .joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = paper.abstract,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PapersListPreview() {
    SortesTheme {
        PapersList(
            papers = listOf(
                Paper(
                    id = 1,
                    title = "Attention Is All You Need",
                    authors = listOf("Ashish Vaswani", "Noam Shazeer"),
                    abstract = "We propose a new simple network architecture, " +
                        "the Transformer, based solely on attention mechanisms.",
                    year = 2017,
                    venue = "NeurIPS",
                ),
            ),
        )
    }
}
