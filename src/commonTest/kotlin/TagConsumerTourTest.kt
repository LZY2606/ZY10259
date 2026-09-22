import kotlinx.html.TagConsumer
import kotlinx.html.consumers.delayed
import kotlinx.html.consumers.filter
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.unsafe
import kotlinx.html.stream.HTMLStreamBuilder
import kotlinx.html.stream.appendHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Executable tour of the TagConsumer event pipeline. See docs/tag-consumer-tour.md.
 *
 * Every test asserts on recorded consumer events ([RecordingTagConsumer]) and/or
 * the serialized stream output, never on a final HTML string alone, so the four
 * layers (DSL source text, consumer events, stream escaping, DOM nodes) stay
 * distinguishable.
 */
class TagConsumerTourTest {

    @Test
    fun recordingPathCapturesFullEventSequence() {
        val recorder = RecordingTagConsumer(BlackHoleTagConsumer())

        recorder.tourFixture()

        assertEquals(expectedTourEvents, recorder.events.rendered(), "events recorded by the recording path")
    }

    @Test
    fun streamPathSeesSameDslEventsAndEscapesOnlyAtSerialization() {
        val out = StringBuilder()
        val recorder = RecordingTagConsumer(out.appendHTML(prettyPrint = false, xhtmlCompatible = false))

        recorder.tourFixture()

        // Layer 1->2: the consumer receives exactly the same events as on the recording path,
        // with raw unescaped text and attribute values.
        assertEquals(expectedTourEvents, recorder.events.rendered(), "events observed in front of the stream backend")
        // Layer 3: the stream backend escapes text and attribute values while serializing.
        assertEquals(expectedTourHtml, out.toString(), "serialized HTML of the stream path")
    }

    @Test
    fun delayedConsumerFoldsAttributeChangesIntoTagStart() {
        val out = StringBuilder()
        // Recorder sits *behind* DelayedConsumer, so it sees what HTMLStreamBuilder sees.
        val inner = RecordingTagConsumer(HTMLStreamBuilder(out, prettyPrint = false, xhtmlCompatible = false))

        inner.delayed().tourFixture()

        assertEquals(
            listOf(
                "start(div, data-raw=\"a\\\"b<c>&d\", class=\"alpha beta\", contenteditable=\"true\")",
                "start(input, checked=\"checked\")",
                "end(input)",
                "start(button, type=\"submit\")",
                "content(\"Go\")",
                "end(button)",
                "content(\"\")",
                "content(\"5 < 6 & \\\"quoted\\\"\")",
                "entity(nbsp)",
                "unsafe(\"<em>raw</em>\")",
                "content(\"<b>escaped again</b>\")",
                "start(span)",
                "end(span)",
                "end(div)",
                "finalize",
            ),
            inner.events.rendered(),
            "events seen behind DelayedConsumer: attribute changes are folded into the flushed tag start",
        )
        assertEquals(expectedTourHtml, out.toString(), "delayed stream produces the same HTML")
    }

    @Test
    fun consumerReceivesRawTextWhileStreamEscapes() {
        val out = StringBuilder()
        val recorder = RecordingTagConsumer(out.appendHTML(prettyPrint = false))

        recorder.div {
            attributes["data-x"] = "<tag>&\"'"
            +"<tag>&\"'"
        }

        // The DSL layer and the consumer layer carry the raw string...
        assertEquals(
            listOf(
                "start(div)",
                "attr(div, data-x=\"<tag>&\\\"'\")",
                "content(\"<tag>&\\\"'\")",
                "end(div)",
                "finalize",
            ),
            recorder.events.rendered(),
            "consumer events carry raw, unescaped values",
        )
        // ...only the stream serialization layer escapes. Attribute values and text
        // content share the same escape set: < > & " (single quotes are not escaped).
        assertEquals(
            "<div data-x=\"&lt;tag&gt;&amp;&quot;'\">&lt;tag&gt;&amp;&quot;'</div>",
            out.toString(),
            "stream output escapes <, >, & and \" in both attribute values and text",
        )
    }

    @Test
    fun unsafeBypassesEscapingOnlyInsideItsBlock() {
        val out = StringBuilder()
        val recorder = RecordingTagConsumer(out.appendHTML(prettyPrint = false))

        recorder.div {
            unsafe {
                +"<b>raw</b>"
            }
            +"<b>escaped again</b>"
        }

        assertEquals(
            listOf(
                "start(div)",
                "unsafe(\"<b>raw</b>\")",
                "content(\"<b>escaped again</b>\")",
                "end(div)",
                "finalize",
            ),
            recorder.events.rendered(),
            "unsafe is a single consumer event, regular content after it stays regular content",
        )
        assertEquals(
            "<div><b>raw</b>&lt;b&gt;escaped again&lt;/b&gt;</div>",
            out.toString(),
            "unsafe output is verbatim; content after the unsafe block is escaped again",
        )
    }

    @Test
    fun exceptionSkipsPendingEndTagsAndFinalize() {
        val recorder = RecordingTagConsumer(BlackHoleTagConsumer())

        val failure = assertFailsWith<FixtureException> {
            recorder.throwingTourFixture()
        }

        assertEquals("boom inside span", failure.message)
        // Current contract: visitTag has no try/finally, so neither end(span) nor end(div)
        // nor finalize happen once the block throws.
        assertEquals(
            listOf(
                "start(div)",
                "content(\"before\")",
                "start(span)",
            ),
            recorder.events.rendered(),
            "events recorded before the exception; no end tags and no finalize afterwards",
        )
    }

    @Test
    fun streamKeepsPartialOutputAfterExceptionAndDropsUnflushedStartTag() {
        val out = StringBuilder()

        assertFailsWith<FixtureException> {
            out.appendHTML(prettyPrint = false).throwingTourFixture()
        }

        // "<div>" was flushed by the first content; the delayed "<span>" start tag was
        // still buffered in DelayedConsumer when the exception hit, so it never reached
        // the stream. No end tags are written either.
        assertEquals("<div>before", out.toString(), "partial stream output after the exception")
    }

    @Test
    fun customConsumerWrapperTransformsEventsInFlight() {
        val recorder = RecordingTagConsumer(BlackHoleTagConsumer())

        UppercaseContentConsumer(recorder).div {
            attributes["data-note"] = "keep"
            +"shout"
        }

        assertEquals(
            listOf(
                "start(div)",
                "attr(div, data-note=\"keep\")",
                "content(\"SHOUT\")",
                "end(div)",
                "finalize",
            ),
            recorder.events.rendered(),
            "the wrapper uppercases text content but leaves attributes and structure untouched",
        )
    }

    @Test
    fun libraryFilterWrapperDropsSubtreeFromEventStream() {
        val out = StringBuilder()

        out.appendHTML(prettyPrint = false)
            .filter { if (it.tagName == "span") DROP else PASS }
            .div {
                +"a"
                span { +"b" }
                +"c"
            }

        assertEquals("<div>ac</div>", out.toString(), "DROP removes the span subtree from the stream")
    }
}

/** A custom [TagConsumer] wrapper used by the tour: uppercases text content, passes everything else through. */
class UppercaseContentConsumer<R>(private val downstream: TagConsumer<R>) : TagConsumer<R> by downstream {
    override fun onTagContent(content: CharSequence) {
        downstream.onTagContent(content.toString().uppercase())
    }
}
