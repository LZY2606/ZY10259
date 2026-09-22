package kotlinx.html.tests.tour

import kotlinx.html.consumers.onFinalizeMap
import kotlinx.html.stream.appendHTML
import kotlinx.html.tests.tour.RecordedEvent.Content
import kotlinx.html.tests.tour.RecordedEvent.Finalize
import kotlinx.html.tests.tour.RecordedEvent.TagStart
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the CURRENT contract for a DSL block that throws mid-tree
 * (see docs/consumer-events-tour.md, "Exception contract"):
 *  - visitTag has no try/finally, so no onTagEnd is emitted for open tags
 *  - the consumer is still usable: finalize() can be called afterwards
 *  - FinalizeConsumer reports partial = true
 */
class ExceptionTourTest {

    @Test
    fun recordingSeesStartsAndContentButNoEndsAfterThrow() {
        val events = RecordingConsumer().tourExceptionFixture()

        assertEquals(
            listOf(
                TagStart("div", emptyMap()),
                Content("before"),
                TagStart("span", emptyMap()),
                Content("doomed"),
                // no TagEnd("span"), no TagEnd("div"): visitTag does not compensate
                Finalize, // finalize() still works and is still recorded
            ).joinToString("\n"),
            events.joinToString("\n"),
        )
    }

    @Test
    fun finalizeWrapperReportsPartialResultAfterThrow() {
        val recording = RecordingConsumer()
        val summary = recording.onFinalizeMap { events, partial ->
            "events=${events.size} partial=$partial"
        }.tourExceptionFixture()

        // FinalizeConsumer counts unbalanced onTagStart/onTagEnd: level never
        // returned to 0, so the result is flagged as partial
        assertEquals("events=5 partial=true", summary)
    }

    @Test
    fun streamKeepsBytesWrittenBeforeThrowAndClosesNoTags() {
        val out = StringBuilder()
        out.appendHTML(prettyPrint = false).tourExceptionFixture()

        // the stream is a single pass: whatever was flushed before the exception
        // stays, and no end tags are synthesized afterwards
        assertEquals("<div>before<span>doomed", out.toString())
    }
}
