package dev.sortes.app.arxiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArxivIdTest {

    private fun assertParses(input: String, id: String, version: Int? = null) {
        assertEquals(input, ArxivId(id, version), ArxivId.parse(input))
    }

    @Test
    fun bareNewStyleIds() {
        assertParses("1706.03762", "1706.03762")
        assertParses("1706.03762v7", "1706.03762", 7)
        assertParses("2401.12345v12", "2401.12345", 12)
        assertParses("  1706.03762\n", "1706.03762")
        // Four-digit sequence numbers (2007–2014).
        assertParses("0704.0001", "0704.0001")
        assertParses("1412.9999v2", "1412.9999", 2)
    }

    @Test
    fun bareOldStyleIds() {
        assertParses("hep-th/9901001", "hep-th/9901001")
        assertParses("hep-th/9901001v3", "hep-th/9901001", 3)
        assertParses("math.GT/0309136", "math.GT/0309136")
        assertParses("cond-mat/0102536v1", "cond-mat/0102536", 1)
        assertParses("Math.gt/0309136", "math.GT/0309136")
    }

    @Test
    fun arxivPrefix() {
        assertParses("arXiv:1706.03762", "1706.03762")
        assertParses("arxiv: 1706.03762v2", "1706.03762", 2)
        assertParses("ARXIV:hep-th/9901001", "hep-th/9901001")
    }

    @Test
    fun absUrls() {
        assertParses("https://arxiv.org/abs/1706.03762", "1706.03762")
        assertParses("arxiv.org/abs/1706.03762v7", "1706.03762", 7)
        assertParses("http://arxiv.org/abs/1706.03762", "1706.03762")
        assertParses("https://www.arxiv.org/abs/1706.03762", "1706.03762")
        assertParses("https://export.arxiv.org/abs/1706.03762", "1706.03762")
        assertParses("HTTPS://ARXIV.ORG/ABS/1706.03762", "1706.03762")
        assertParses("https://arxiv.org/abs/1706.03762/", "1706.03762")
        assertParses("https://arxiv.org/abs/1706.03762?context=cs", "1706.03762")
        assertParses("https://arxiv.org/abs/1706.03762#S1", "1706.03762")
        assertParses("https://arxiv.org/abs/hep-th/9901001v2", "hep-th/9901001", 2)
        assertParses("https://arxiv.org/abs/math.GT/0309136", "math.GT/0309136")
    }

    @Test
    fun pdfUrls() {
        assertParses("https://arxiv.org/pdf/1706.03762", "1706.03762")
        assertParses("https://arxiv.org/pdf/1706.03762v7", "1706.03762", 7)
        assertParses("https://arxiv.org/pdf/1706.03762.pdf", "1706.03762")
        assertParses("arxiv.org/pdf/1706.03762v7.pdf", "1706.03762", 7)
        assertParses("https://arxiv.org/pdf/hep-th/9901001.pdf", "hep-th/9901001")
        assertParses("https://export.arxiv.org/pdf/2401.12345v1", "2401.12345", 1)
    }

    @Test
    fun alphaxivPaperUrls() {
        assertParses("https://www.alphaxiv.org/abs/2311.08105", "2311.08105")
        assertParses("https://alphaxiv.org/abs/2311.08105v2", "2311.08105", 2)
        assertParses("alphaxiv.org/abs/2311.08105", "2311.08105")
        assertParses("https://www.alphaxiv.org/overview/2311.08105", "2311.08105")
        assertParses("https://www.alphaxiv.org/pdf/2311.08105v1", "2311.08105", 1)
        assertParses("https://www.alphaxiv.org/abs/2311.08105.pdf", "2311.08105")
        assertParses("https://www.alphaxiv.org/abs/2311.08105.md", "2311.08105")
        assertParses("https://www.alphaxiv.org/es/abs/2311.08105", "2311.08105")
        assertParses("https://www.alphaxiv.org/zh-CN/overview/2311.08105", "2311.08105")
        assertParses("https://www.alphaxiv.org/abs/2311.08105?tab=comments#c1", "2311.08105")
        assertParses("https://www.alphaxiv.org/abs/hep-th/9901001", "hep-th/9901001")
        assertParses(
            "Check this out on alphaXiv: https://www.alphaxiv.org/abs/2311.08105",
            "2311.08105",
        )
    }

    @Test
    fun otherAlphaxivPagesAreNotPapers() {
        assertNull(
            ArxivId.parse(
                "https://www.alphaxiv.org/shared/folder/01a1838a-db89-71d0-956c-e8067fdfee2a"
            )
        )
        assertNull(ArxivId.parse("https://www.alphaxiv.org/audio/2311.08105"))
        assertNull(ArxivId.parse("https://www.alphaxiv.org/html/2311.08105"))
        assertNull(ArxivId.parse("https://notalphaxiv.org/abs/2311.08105"))
        assertNull(ArxivId.parse("https://alphaxiv.org.evil.com/abs/2311.08105"))
        assertNull(ArxivId.parse("https://www.alphaxiv.org/toolong/abs/2311.08105"))
    }

    @Test
    fun htmlUrls() {
        assertParses("https://arxiv.org/html/2401.12345v2", "2401.12345", 2)
        assertParses("https://arxiv.org/html/2401.12345v2/#S3", "2401.12345", 2)
    }

    @Test
    fun findsIdsInSharedText() {
        assertParses(
            "Attention Is All You Need https://arxiv.org/abs/1706.03762",
            "1706.03762",
        )
        assertParses("Read this:\nhttps://arxiv.org/pdf/1706.03762v5 (great)", "1706.03762", 5)
        assertParses("See arxiv.org/abs/1706.03762.", "1706.03762")
        assertParses("Vaswani et al., arXiv:1706.03762, 2017", "1706.03762")
        assertParses("<https://arxiv.org/abs/1706.03762>", "1706.03762")
    }

    @Test
    fun pdfUrlIsVersionless() {
        assertEquals("https://arxiv.org/pdf/1706.03762", ArxivId.parse("1706.03762v7")!!.pdfUrl)
        assertEquals(
            ArxivId.parse("1706.03762")!!.pdfUrl,
            ArxivId.parse("https://arxiv.org/abs/1706.03762v2")!!.pdfUrl,
        )
        assertEquals(
            "https://arxiv.org/pdf/hep-th/9901001",
            ArxivId.parse("hep-th/9901001v3")!!.pdfUrl,
        )
    }

    @Test
    fun rejectsBadInput() {
        listOf(
                "",
                "   ",
                "hello",
                "1706",
                "1706.037",
                "1706.037621",
                "17060.3762",
                "1713.03762", // month 13
                "1700.03762", // month 00
                "1706.03762v",
                "1706.03762vx",
                "1706.03762abc",
                "abs/1706.03762",
                "hep-th/990100",
                "hep-th/99010012",
                "/9901001",
                "https://example.com/abs/1706.03762",
                "https://notarxiv.org/abs/1706.03762",
                "https://evil-arxiv.org/abs/1706.03762",
                "https://arxiv.org.evil.com/abs/1706.03762",
                "https://arxiv.org/list/cs.LG/recent",
                "https://arxiv.org/abs/",
                "https://arxiv.org/abs/1706.03762abc",
                "https://arxiv.org/abs/1706.037621",
                "The answer is 1706.03762", // bare IDs only count on their own or after arXiv:
            )
            .forEach { assertNull(it, ArxivId.parse(it)) }
    }
}
