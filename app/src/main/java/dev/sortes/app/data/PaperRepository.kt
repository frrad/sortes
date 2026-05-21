package dev.sortes.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class PaperRepository(private val dao: PaperDao) {

    fun observeAll(): Flow<List<Paper>> = dao.observeAll()

    suspend fun seedIfEmpty() {
        if (dao.count() == 0) {
            dao.insertAll(SAMPLE_PAPERS)
        }
    }

    companion object {
        fun from(context: Context): PaperRepository =
            PaperRepository(PaperDatabase.get(context).paperDao())

        private val SAMPLE_PAPERS = listOf(
            Paper(
                title = "Attention Is All You Need",
                authors = listOf(
                    "Ashish Vaswani", "Noam Shazeer", "Niki Parmar", "Jakob Uszkoreit",
                ),
                abstract = "We propose a new simple network architecture, the " +
                    "Transformer, based solely on attention mechanisms, dispensing " +
                    "with recurrence and convolutions entirely.",
                year = 2017,
                venue = "NeurIPS",
            ),
            Paper(
                title = "Deep Residual Learning for Image Recognition",
                authors = listOf("Kaiming He", "Xiangyu Zhang", "Shaoqing Ren", "Jian Sun"),
                abstract = "We present a residual learning framework to ease the " +
                    "training of networks that are substantially deeper than those " +
                    "used previously.",
                year = 2016,
                venue = "CVPR",
            ),
            Paper(
                title = "BERT: Pre-training of Deep Bidirectional Transformers " +
                    "for Language Understanding",
                authors = listOf(
                    "Jacob Devlin", "Ming-Wei Chang", "Kenton Lee", "Kristina Toutanova",
                ),
                abstract = "We introduce BERT, which stands for Bidirectional Encoder " +
                    "Representations from Transformers, designed to pre-train deep " +
                    "bidirectional representations from unlabeled text.",
                year = 2019,
                venue = "NAACL",
            ),
        )
    }
}
