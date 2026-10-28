package dev.sortes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    PapersList(papers = papers, onPaperClick = onPaperClick, modifier = modifier)
}

@Composable
internal fun PapersList(
    papers: List<Paper>,
    modifier: Modifier = Modifier,
    onPaperClick: (Paper) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Papers",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
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
