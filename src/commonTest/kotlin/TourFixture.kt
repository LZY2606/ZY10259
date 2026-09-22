import kotlinx.html.ButtonType
import kotlinx.html.Entities
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.contentEditable
import kotlinx.html.div
import kotlinx.html.input
import kotlinx.html.span
import kotlinx.html.unsafe

/**
 * The shared DSL fixture of the TagConsumer tour (see docs/tag-consumer-tour.md).
 *
 * The very same block drives three backends in the tests:
 *  - the recording path ([RecordingTagConsumer] over [BlackHoleTagConsumer]),
 *  - the stream path (`kotlinx.html.stream.appendHTML`),
 *  - the DOM path (`kotlinx.html.dom.create`, JVM only).
 *
 * Covered features: raw map attribute with special characters, class set attribute,
 * boolean attribute (encoded), ticker boolean attribute (put/remove), enum attribute,
 * nested tags, an empty tag, empty text, special characters in text, an entity,
 * an unsafe block, and regular text after the unsafe block.
 *
 * Attribute writes always happen before any content of the same tag so that the
 * fixture also works through `DelayedConsumer` on the stream path.
 */
fun <R> TagConsumer<R>.tourFixture(): R = div {
    attributes["data-raw"] = "a\"b<c>&d"
    classes = setOf("alpha", "beta")
    contentEditable = true

    input {
        checked = true
        checked = false
        checked = true
    }
    button {
        type = ButtonType.submit
        +"Go"
    }
    +""
    +"5 < 6 & \"quoted\""
    +Entities.nbsp
    unsafe {
        +"<em>raw</em>"
    }
    +"<b>escaped again</b>"
    span { }
}

/** Exception thrown by [throwingTourFixture]. */
class FixtureException(message: String) : RuntimeException(message)

/**
 * Fixture that throws while `div` and `span` are still open.
 * Used to pin down the current contract for exceptions: no pending `onTagEnd`
 * calls and no `finalize` happen once a DSL block throws.
 */
fun <R> TagConsumer<R>.throwingTourFixture(): R = div {
    +"before"
    span {
        throw FixtureException("boom inside span")
    }
}

/** Events the recording path must see for [tourFixture]. Shared with the JVM DOM test. */
val expectedTourEvents: List<String> = listOf(
    "start(div)",
    "attr(div, data-raw=\"a\\\"b<c>&d\")",
    "attr(div, class=\"alpha beta\")",
    "attr(div, contenteditable=\"true\")",
    "start(input)",
    "attr(input, checked=\"checked\")",
    "attr(input, checked=null)",
    "attr(input, checked=\"checked\")",
    "end(input)",
    "start(button)",
    "attr(button, type=\"submit\")",
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
)

/** HTML the stream path must produce for [tourFixture] with `prettyPrint = false`, `xhtmlCompatible = false`. */
val expectedTourHtml: String =
    "<div data-raw=\"a&quot;b&lt;c&gt;&amp;d\" class=\"alpha beta\" contenteditable=\"true\">" +
        "<input checked=\"checked\">" +
        "<button type=\"submit\">Go</button>" +
        "5 &lt; 6 &amp; &quot;quoted&quot;" +
        "&nbsp;" +
        "<em>raw</em>" +
        "&lt;b&gt;escaped again&lt;/b&gt;" +
        "<span></span>" +
        "</div>"
