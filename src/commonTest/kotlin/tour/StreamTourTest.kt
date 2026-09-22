package kotlinx.html.tests.tour

import kotlinx.html.Entities
import kotlinx.html.div
import kotlinx.html.stream.appendHTML
import kotlinx.html.stream.createHTML
import kotlinx.html.unsafe
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stream backend: same fixture, observed as serialized HTML (layer 3).
 * Escaping happens here, inside HTMLStreamBuilder, not in the DSL and not in
 * the consumer interface.
 */
class StreamTourTest {

    @Test
    fun fixtureSerializesToEscapedHtml() {
        val html = createHTML(prettyPrint = false).tourFixture()

        assertEquals(
            "<div id=\"tour-root\" class=\"alpha beta\" data-raw=\"5 &lt; 6 &amp; &quot;ok&quot;\">" +
                "<input disabled=\"disabled\">" +
                "<button type=\"submit\">Go</button>" +
                "<span>Fish &amp; Chips &lt;3 &quot;quoted&quot;" +
                "&copy;" + // entity: emitted as-is, never re-escaped
                "<b>raw</b>" + // unsafe: appended verbatim
                "still &lt;escaped&gt; &amp; safe" + // ordinary content after unsafe is escaped again
                "</span></div>",
            html,
        )
    }

    @Test
    fun attributeValuesAndTextContentAreEscapedSeparately() {
        val html = createHTML(prettyPrint = false).div {
            attributes["data-text"] = "<&>\""
            +"<&>\""
        }

        // escapeMap covers < > & " for BOTH attribute values and text content
        assertEquals(
            "<div data-text=\"&lt;&amp;&gt;&quot;\">&lt;&amp;&gt;&quot;</div>",
            html,
        )
    }

    @Test
    fun backslashAmpersandIsAnEscapeHatch() {
        val html = createHTML(prettyPrint = false).div {
            +"\\&copy;" // produces a literal &copy; without using an entity event
            +"\\&" // a lone literal &
        }

        assertEquals("<div>&copy;&</div>", html)
    }

    @Test
    fun entityEventsAreNeverEscaped() {
        val html = createHTML(prettyPrint = false).div {
            entity(Entities.amp) // &amp; written directly, NOT &amp;amp;
        }

        assertEquals("<div>&amp;</div>", html)
    }

    @Test
    fun unsafeBypassIsScopedToItsOwnBlock() {
        val html = createHTML(prettyPrint = false).div {
            +"<before>"
            unsafe { +"<u>raw</u>" }
            +"<after>"
        }

        assertEquals("<div>&lt;before&gt;<u>raw</u>&lt;after&gt;</div>", html)
    }

    @Test
    fun delayedFoldsLateAttributeWritesIntoStartTag() {
        // appendHTML() = HTMLStreamBuilder(...).delayed(): the DelayedConsumer holds back
        // onTagStart until the first content/end, so attribute assignments inside the block
        // are already present in tag.attributes when the start tag is serialized.
        val out = StringBuilder()
        out.appendHTML(prettyPrint = false).div {
            attributes["data-late"] = "yes"
            +"body"
        }

        assertEquals("<div data-late=\"yes\">body</div>", out.toString())
    }
}
