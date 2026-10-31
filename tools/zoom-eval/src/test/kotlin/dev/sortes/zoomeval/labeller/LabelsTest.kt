package dev.sortes.zoomeval.labeller

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    private val against =
        LabelledAgainst(
            commit = "abc123def456",
            algorithm = "reader",
            weights = mapOf("alpha" to 1.0, "beta" to 0.1, "epsilon" to 300.0, "maxWindows" to 3.0),
            scale = 0.835,
            aspect = 0.75,
        )

    private val labels =
        PaperLabels(
            paper = "1706.03762",
            labelledAgainst = against,
            idealScale = 0.8,
            comment = "Zoom in a bit: \"too loose\" on most pages.\nSecond line.",
            pages =
                mapOf(
                    1 to
                        PageLabel(
                            Verdict.BAD,
                            setOf(Tag.TOP_CUT, Tag.OTHER),
                            "Notice cut",
                            Point(306.0, 400.5),
                        ),
                    2 to PageLabel(Verdict.GOOD, emptySet(), "", null),
                    14 to PageLabel(null, setOf(Tag.WINDOW_JUMPED), "", null),
                ),
        )

    @Test
    fun roundTripsThroughText() {
        val text = labels.toText()
        assertEquals(labels, PaperLabels.fromText(text))
    }

    @Test
    fun recordedCentresAndParametersRoundTripAndOlderLabelsHaveNone() {
        val recorded =
            labels.copy(
                labelledAgainst =
                    against.copy(
                        algorithm = "tuned+text",
                        centres = listOf(Point(306.0, 396.0), Point(300.5, 401.25)),
                        parameters = mapOf("lineWeight" to 0.02, "textComfortablePx" to 28.0),
                    )
            )
        assertEquals(recorded, PaperLabels.fromText(recorded.toText()))
        assertEquals(recorded.toText(), PaperLabels.fromText(recorded.toText()).toText())
        assertFalse(labels.toText().contains("centres"))
        assertFalse(labels.toText().contains("parameters"))
        assertEquals(null, PaperLabels.fromText(labels.toText()).labelledAgainst!!.centres)
    }

    @Test
    fun anAgentsLabelsSayWhoJudgedAndTheOwnersDoNot() {
        val agent = labels.copy(labeller = "agent")
        assertEquals(agent, PaperLabels.fromText(agent.toText()))
        assertTrue(agent.toText().contains("\"labeller\": \"agent\""))
        assertFalse(labels.toText().contains("labeller"))
        assertEquals(null, PaperLabels.fromText(labels.toText()).labeller)
    }

    @Test
    fun savingIsIdempotent() {
        val once = labels.toText()
        val twice = PaperLabels.fromText(once).toText()
        assertEquals(once, twice)
        // Canonical: keys sorted, so the same labels built in another order write the same text.
        val reordered = labels.copy(pages = labels.pages.toList().reversed().toMap())
        assertEquals(once, reordered.toText())
        assertTrue(once.endsWith("}\n"))
        assertTrue(once.indexOf("\"comment\"") < once.indexOf("\"idealScale\""))
    }

    @Test
    fun pagesWithNothingSaidAreLeftOut() {
        val withEmpty =
            labels.copy(pages = labels.pages + (3 to PageLabel(null, emptySet(), "", null)))
        assertEquals(labels.pages.keys, PaperLabels.fromText(withEmpty.toText()).pages.keys)
        assertTrue(PageLabel(null, emptySet(), "", null).isEmpty)
        assertFalse(PageLabel(null, emptySet(), "", Point(1.0, 2.0)).isEmpty)
    }

    @Test
    fun emptyLabelsRoundTrip() {
        val empty = PaperLabels.empty("shannon1948")
        assertEquals(empty, PaperLabels.fromText(empty.toText()))
        assertTrue(empty.toText().contains("\"labelledAgainst\": null"))
    }

    @Test
    fun saveAndLoadRestoreTheLabels() {
        val dir = Files.createTempDirectory("labels").toFile()
        try {
            assertEquals(PaperLabels.empty("1706.03762"), PaperLabels.load(dir, "1706.03762"))
            val file = labels.save(dir)
            assertEquals("1706.03762.json", file.name)
            assertEquals(labels, PaperLabels.load(dir, "1706.03762"))
            // Saving the same labels again leaves the file byte for byte the same.
            val before = file.readBytes()
            PaperLabels.load(dir, "1706.03762").save(dir)
            assertTrue(before.contentEquals(file.readBytes()))
            // No temporary files left behind.
            assertEquals(listOf("1706.03762.json"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun badLabelsAreRejected() {
        val good = labels.toJson()
        fun bad(edit: (org.json.JSONObject) -> Unit) {
            val json = org.json.JSONObject(good.toString())
            edit(json)
            assertThrows(Exception::class.java) { PaperLabels.fromJson(json) }
        }
        bad { it.put("schema", 2) }
        bad { it.put("paper", "../etc/passwd") }
        bad { it.put("idealScale", 1.5) }
        bad { it.getJSONObject("pages").getJSONObject("1").put("verdict", "meh") }
        bad {
            it.getJSONObject("pages")
                .getJSONObject("1")
                .put("tags", org.json.JSONArray(listOf("bogus")))
        }
        bad { it.getJSONObject("pages").put("zero", it.getJSONObject("pages").getJSONObject("1")) }
        bad { it.getJSONObject("pages").put("0", it.getJSONObject("pages").getJSONObject("1")) }
        assertThrows(IllegalArgumentException::class.java) {
            PaperLabels.fileFor(Files.createTempDirectory("x").toFile(), "a/b")
        }
    }

    @Test
    fun canonicalJsonSortsKeysAndWritesWholeNumbersPlainly() {
        val json =
            org.json
                .JSONObject()
                .put("b", 2.0)
                .put("a", org.json.JSONArray(listOf(0.5, "x", null)))
                .put("c", org.json.JSONObject())
        assertEquals(
            "{\n  \"a\": [\n    0.5,\n    \"x\",\n    null\n  ],\n  \"b\": 2,\n  \"c\": {}\n}",
            canonical(json),
        )
    }

    @Test
    fun everyCommittedLabelRecordsTheCropsItJudged() {
        // Scoring reads the judged crops from the labels, never from model code.
        val files =
            listOf(File("labels"), File("labels/agent")).flatMap { d ->
                d.listFiles { f -> f.extension == "json" }.orEmpty().toList()
            }
        assertTrue(files.size >= 28)
        for (file in files) {
            val labels = PaperLabels.fromText(file.readText())
            val centres = labels.labelledAgainst?.centres
            assertTrue("${file.path}: no centres", centres != null && centres.isNotEmpty())
            assertTrue(
                "${file.path}: a page past the centres",
                labels.pages.keys.all { it <= centres!!.size },
            )
        }
    }
}
