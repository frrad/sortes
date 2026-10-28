package dev.sortes.app.importing

import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PaperDao] for JVM tests, with the same unique-pdfUrl rule as the real table. */
class FakePaperDao : PaperDao {
    val papers = MutableStateFlow<List<Paper>>(emptyList())
    var failInserts = false

    /** Runs after a row is committed, before [insert] returns, like Room resuming its caller. */
    var afterInsert: suspend () -> Unit = {}
    private var nextId = 1L

    override fun observeAll(): Flow<List<Paper>> = papers

    override suspend fun count(): Int = papers.value.size

    override suspend fun insertAll(papers: List<Paper>) {
        papers.forEach { insert(it) }
    }

    override suspend fun insert(paper: Paper): Long {
        if (failInserts) throw IllegalStateException("insert failed")
        if (paper.pdfUrl != null && papers.value.any { it.pdfUrl == paper.pdfUrl }) return -1
        val id = nextId++
        papers.value = papers.value + paper.copy(id = id)
        afterInsert()
        return id
    }

    override suspend fun findByPdfUrl(url: String): Paper? = papers.value.find { it.pdfUrl == url }
}
