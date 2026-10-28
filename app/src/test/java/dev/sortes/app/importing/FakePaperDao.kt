package dev.sortes.app.importing

import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PaperDao] for JVM tests. */
class FakePaperDao : PaperDao {
    val papers = MutableStateFlow<List<Paper>>(emptyList())
    var failInserts = false
    private var nextId = 1L

    override fun observeAll(): Flow<List<Paper>> = papers

    override suspend fun count(): Int = papers.value.size

    override suspend fun insertAll(papers: List<Paper>) {
        papers.forEach { insert(it) }
    }

    override suspend fun insert(paper: Paper): Long {
        if (failInserts) throw IllegalStateException("insert failed")
        val id = nextId++
        papers.value = papers.value + paper.copy(id = id)
        return id
    }

    override suspend fun findByPdfUrl(url: String): Paper? = papers.value.find { it.pdfUrl == url }
}
