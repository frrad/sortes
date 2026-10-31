package dev.sortes.app.arxiv

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArxivAtomTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun parse(xml: String) = ArxivAtom.parse(xml.byteInputStream(), "1706.03762")

    @Test
    fun parsesEntry() {
        val entry = parse(ATTENTION_FEED)

        assertEquals("Attention Is All You Need", entry.title)
        assertEquals(listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar"), entry.authors)
        assertEquals(
            "The dominant sequence transduction models are based on complex recurrent or " +
                "convolutional neural networks.",
            entry.abstract,
        )
        assertEquals(2017, entry.year)
    }

    @Test
    fun ignoresNonAtomElementsWithSameNames() {
        // arxiv:comment / arxiv:affiliation etc. live in another namespace and must not leak in.
        val entry = parse(ATTENTION_FEED)
        assertFalse(entry.title.contains("pages"))
        assertFalse(entry.authors.contains("Google Brain"))
    }

    @Test
    fun missingPublishedDateGivesNullYear() {
        val entry = parse(feed("<entry><id>x</id><title>T</title></entry>"))
        assertEquals(null, entry.year)
        assertEquals(emptyList<String>(), entry.authors)
        assertEquals("", entry.abstract)
    }

    @Test
    fun errorEntryThrowsWithArxivMessage() {
        val e = assertThrows(IOException::class.java) { parse(ERROR_FEED) }
        assertTrue(e.message!!, e.message!!.contains("incorrect id format for 1234"))
    }

    @Test
    fun emptyFeedThrows() {
        val e = assertThrows(IOException::class.java) { parse(feed("")) }
        assertTrue(e.message!!, e.message!!.contains("1706.03762"))
    }

    @Test
    fun entryWithoutTitleThrows() {
        assertThrows(IOException::class.java) {
            parse(feed("<entry><id>http://arxiv.org/abs/1706.03762</id><title> </title></entry>"))
        }
    }

    @Test
    fun malformedXmlThrowsIOException() {
        assertThrows(IOException::class.java) { parse("<feed><entry>") }
        assertThrows(IOException::class.java) { parse("<html>Service unavailable</html") }
        assertThrows(IOException::class.java) { parse("") }
    }

    @Test
    fun doesNotResolveExternalEntities() {
        val secret = tmp.newFile("secret.txt").apply { writeText("TOP-SECRET") }
        val xml =
            """<?xml version="1.0"?>
            <!DOCTYPE feed [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>x</id><title>&xxe;</title></entry>
            </feed>"""

        val result = runCatching { parse(xml) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("Unexpected DOCTYPE in arXiv response", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsAnyDoctypeBeforeParsing() {
        // Even a harmless DTD is refused: on Android the parser can't be told to ignore one.
        val xml =
            """<?xml version="1.0"?><!doctype feed><feed xmlns="http://www.w3.org/2005/Atom"/>"""

        val e = assertThrows(IOException::class.java) { parse(xml) }
        assertEquals("Unexpected DOCTYPE in arXiv response", e.message)
    }

    @Test
    fun rejectsDoctypeHiddenInUtf16() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE feed><feed/>"""

        val e =
            assertThrows(IOException::class.java) {
                ArxivAtom.parse(xml.toByteArray(Charsets.UTF_16).inputStream(), "1706.03762")
            }
        assertEquals("Unexpected encoding in arXiv response", e.message)
    }

    @Test
    fun rejectsOversizedResponse() {
        val huge = feed("<!-- " + "x".repeat(3 * 1024 * 1024) + " -->")

        val e = assertThrows(IOException::class.java) { parse(huge) }
        assertEquals("arXiv response too large", e.message)
    }

    companion object {
        fun feed(entries: String) =
            """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/"
                  xmlns:arxiv="http://arxiv.org/schemas/atom">
              <title type="html">ArXiv Query: id_list=1706.03762</title>
              <id>http://arxiv.org/api/abc</id>
              <opensearch:totalResults>${if (entries.isEmpty()) 0 else 1}</opensearch:totalResults>
              $entries
            </feed>"""

        val ATTENTION_FEED =
            feed(
                """<entry>
                <id>http://arxiv.org/abs/1706.03762v7</id>
                <updated>2023-08-02T00:41:18Z</updated>
                <published>2017-06-12T17:57:34Z</published>
                <title>Attention Is All
                  You Need</title>
                <summary>  The dominant sequence transduction models are based on complex
                recurrent or convolutional neural networks.
                </summary>
                <author><name>Ashish Vaswani</name><arxiv:affiliation>Google Brain</arxiv:affiliation></author>
                <author><name>Noam Shazeer</name></author>
                <author><name>Niki Parmar</name></author>
                <arxiv:comment>15 pages, 5 figures</arxiv:comment>
                <arxiv:title>Not the title</arxiv:title>
                <link href="http://arxiv.org/abs/1706.03762v7" rel="alternate" type="text/html"/>
                <link title="pdf" href="http://arxiv.org/pdf/1706.03762v7" rel="related" type="application/pdf"/>
                <arxiv:primary_category term="cs.CL" scheme="http://arxiv.org/schemas/atom"/>
              </entry>"""
            )

        val ERROR_FEED =
            feed(
                """<entry>
                <id>http://arxiv.org/api/errors#incorrect_id_format_for_1234</id>
                <title>Error</title>
                <summary>incorrect id format for 1234</summary>
                <updated>2026-10-28T00:00:00-04:00</updated>
                <link href="http://arxiv.org/api/errors#incorrect_id_format_for_1234" rel="alternate" type="text/html"/>
                <author><name>arXiv api core</name></author>
              </entry>"""
            )
    }
}
