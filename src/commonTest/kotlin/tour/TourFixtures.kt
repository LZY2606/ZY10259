package kotlinx.html.tests.tour

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.Entities
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.emptyMap
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.span
import kotlinx.html.unsafe
import kotlinx.html.visitTag

/**
 * The single DSL fixture shared by every backend tour test:
 * recording (commonTest), stream (commonTest) and DOM (jvmTest).
 *
 * Covers, in order of appearance:
 *  - plain, boolean-ticker and enum attributes, plus a class set
 *  - an attribute value with characters that need escaping
 *  - nested tags (div > input/button/span) and an empty tag (input)
 *  - text with special characters, and an empty text node
 *  - an entity, an unsafe block, and ordinary text AFTER the unsafe block
 *    (to prove unsafe does not leak escaping bypass to later content)
 *
 * The unsafe payload is well-formed XML (`<b>raw</b>`) because the JVM DOM consumer
 * parses unsafe content with an XML parser; the JS DOM consumer uses innerHTML and
 * the stream consumer appends it verbatim. See docs/consumer-events-tour.md.
 */
fun <R> TagConsumer<R>.tourFixture(): R = div {
    id = "tour-root"
    classes = setOf("alpha", "beta")
    attributes["data-raw"] = "5 < 6 & \"ok\""

    input {
        disabled = true
    }
    button {
        type = ButtonType.submit
        +"Go"
    }
    span {
        +"Fish & Chips <3 \"quoted\""
        +""
        entity(Entities.copy)
        unsafe { +"<b>raw</b>" }
        +"still <escaped> & safe"
    }
}

/** Thrown inside [tourExceptionFixture] to interrupt the DSL mid-tree. */
class TourFixtureException : RuntimeException("boom from tour fixture")

/**
 * Second shared fixture: a tag tree whose DSL block throws half-way through.
 *
 * The exception is caught inside the fixture so the SAME fixture can drive every
 * backend; each test then asserts what its backend recorded/printed afterwards.
 * `finalize()` is called explicitly at the end, because the generated
 * `consumer.div { }` entry points never get a chance to run their own
 * visitAndFinalize once the block throws.
 *
 * Current contract being pinned by the tests (see docs/consumer-events-tour.md):
 *  - visitTag has no try/finally: NO onTagEnd is emitted for `span` or `div`
 *  - the consumer is still usable afterwards; finalize() works
 *  - FinalizeConsumer reports partial = true because its level never returned to 0
 */
fun <R> TagConsumer<R>.tourExceptionFixture(): R {
    val root = DIV(emptyMap, this)
    try {
        root.visitTag {
            +"before"
            span {
                +"doomed"
                throw TourFixtureException()
            }
        }
    } catch (expected: TourFixtureException) {
        // deliberately swallowed: the tour is about what the consumer observed
    }
    return finalize()
}
