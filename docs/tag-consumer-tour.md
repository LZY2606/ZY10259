# TagConsumer event pipeline: an executable tour

This document explains how a kotlinx.html DSL block becomes output: how tags,
attributes, text, entities, `unsafe` blocks, `finalize` and consumer wrappers
propagate through the layers. Every event sequence quoted here is asserted
by a test, so the document cannot silently drift away from the implementation.

| Role | File |
| --- | --- |
| Recording consumer (test infrastructure) | `src/commonTest/kotlin/RecordingTagConsumer.kt` |
| Shared DSL fixture + expected events/HTML | `src/commonTest/kotlin/TourFixture.kt` |
| Recording & stream path tests (all platforms) | `src/commonTest/kotlin/TagConsumerTourTest.kt` |
| DOM path tests (JVM) | `src/jvmTest/kotlin/tour-dom.kt` |

Run the tour with `./gradlew jvmTest` (JVM, includes DOM) or `./gradlew check`
(all supported targets; the common tests run on every platform, the DOM tests are
JVM-only and need no browser).

## Layers

```
 DSL block (Tag extensions, attribute delegates, +"...", entity, unsafe)
        |  visitTag / visitTagAndFinalize            (src/commonMain/kotlin/visit.kt)
        v
 TagConsumer events                                  (src/commonMain/kotlin/api.kt)
        |  optional wrappers: delayed / filter / onFinalizeMap / trace / measureTime / custom
        |                                            (src/commonMain/kotlin/*-consumer.kt)
        v
 backend consumer
        |-- HTMLStreamBuilder  -> Appendable         (src/commonMain/kotlin/stream.kt)
        |-- HTMLDOMBuilder     -> org.w3c.dom (JVM)  (src/jvmMain/kotlin/dom-jvm.kt)
        `-- JSDOMBuilder       -> DOM (JS / WasmJS)  (src/jsMain/kotlin/dom-js.kt,
                                                     src/wasmJsMain/kotlin/dom-js.kt)
```

A DSL call such as `div { ... }` constructs a tag object (`HTMLTag`,
`src/commonMain/kotlin/htmltag.kt`) and `visitTag` emits
`onTagStart` → block → `onTagEnd`. The top-level call goes through
`visitTagAndFinalize`, which additionally calls `consumer.finalize()` after the
root tag ends. `finalize` therefore happens once per top-level DSL expression,
not per tag.

## One fixture, three paths

`tourFixture()` (`src/commonTest/kotlin/TourFixture.kt`) exercises raw map
attributes, a class set, an encoded boolean, a ticker boolean, an enum
attribute, nested tags, an empty tag, empty text, special characters, an
entity, an `unsafe` block and regular text after it. The same block drives:

1. the **recording path** — `RecordingTagConsumer` over a no-op sink
   (`TagConsumerTourTest`: `recording path captures the full event sequence`),
2. the **stream path** — `RecordingTagConsumer` over `appendHTML`
   (`TagConsumerTourTest`: `stream path sees the same dsl events and escapes only at serialization`),
3. the **DOM path** — `RecordingTagConsumer` over `document.create` (JVM,
   `TourDomTest`: `dom path receives the same dsl events as the recording path`).

All three record the identical event sequence, proving the DSL → consumer
layer is backend-independent:

```
start(div)
attr(div, data-raw="a\"b<c>&d")        // attributes["data-raw"] = ... (raw map write)
attr(div, class="alpha beta")          // classes = setOf("alpha", "beta")  (StringSetAttribute)
attr(div, contenteditable="true")      // contentEditable = true            (BooleanAttribute)
start(input)
attr(input, checked="checked")         // checked = true   (TickerAttribute: put)
attr(input, checked=null)              // checked = false  (TickerAttribute: remove)
attr(input, checked="checked")         // checked = true
end(input)
start(button)
attr(button, type="submit")            // type = ButtonType.submit (EnumAttribute: realValue)
content("Go")
end(button)
content("")                            // +"" : an empty text event is still an event
content("5 < 6 & \"quoted\"")          // special characters travel raw
entity(nbsp)                           // +Entities.nbsp
unsafe("<em>raw</em>")                 // unsafe { +"<em>raw</em>" }
content("<b>escaped again</b>")        // regular text after unsafe
start(span)
end(span)
end(div)
finalize
```

### How each DSL feature becomes an event

| DSL call | Mechanism | Event(s) |
| --- | --- | --- |
| `div { ... }` | generated function (`generated/gen-consumer-tags.kt`) + `visitTag` | `start(div)` … `end(div)` |
| `attributes["k"] = v` | `DelegatingMap.put` (`delegating-map.kt`) | `attr(tag, k="v")`; removal sends `attr(tag, k=null)` |
| `classes = setOf(...)` | `StringSetAttribute` (`attributes.kt`) joins with spaces | `attr(tag, class="a b")` |
| `contentEditable = true` | `BooleanAttribute` encodes `true`/`false` | `attr(tag, contenteditable="true")` |
| `checked = true/false` | `TickerAttribute` puts or **removes** the attribute | `attr(tag, checked="checked")` / `attr(tag, checked=null)` |
| `type = ButtonType.submit` | `EnumAttribute` uses `AttributeEnum.realValue` | `attr(tag, type="submit")` |
| `+"text"` | `Tag.text` (`api.kt`) | `content("text")` |
| `+Entities.nbsp` | `Tag.entity` | `entity(nbsp)` |
| `unsafe { +"<em/>" }` | `HTMLTag.unsafe` (`api.kt`) | one `unsafe(...)` event |
| top-level block end | `visitTagAndFinalize` | `finalize` |

Attribute values set through delegates are just map writes: the delegate only
encodes/decodes (`attributes.kt`), and `DelegatingMap` fires
`onTagAttributeChange` when the value actually changes. Attributes passed to a
tag constructor (e.g. `a("http://...")`) instead arrive inside the tag's
initial attribute map and are visible in the `start(...)` snapshot, not as
`attr` events.

## Four layers of text — do not infer events from HTML

The tour keeps these layers separate on purpose:

1. **DSL source text** — the Kotlin string literal, e.g. `"5 < 6 & \"quoted\""`.
2. **Consumer content** — `onTagContent` receives the *raw, unescaped* string
   (`TagConsumerTourTest`: `consumer receives raw text while the stream escapes it` asserts the
   recorded event is `content("5 < 6 & \"quoted\"")`).
3. **Stream serialization** — `HTMLStreamBuilder` escapes `<`, `>`, `&`, `"`
   via `escapeAppend` (`stream.kt`), producing
   `5 &lt; 6 &amp; &quot;quoted&quot;`. Attribute values and text content share
   the same escape set; single quotes are *not* escaped. The `\&` sequence is
   an escape hatch that emits a literal `&` (see `EscapeAppendTest`).
4. **DOM nodes** — `HTMLDOMBuilder` stores raw data:
   `document.createTextNode(raw)` and `element.setAttribute(name, raw)`
   (`TourDomTest`: `dom stores raw unescaped attribute values`,
   `dom stores raw text nodes and materializes entities and unsafe markup`).
   Escaping is deferred to whatever serializes the DOM later.

So an HTML string can only tell you what the stream backend did; it says
nothing about what events a consumer received. Assert on events
(`RecordingTagConsumer`) or on DOM nodes, not on serialized output, when you
care about the middle layers.

## Entities per backend

`entity(nbsp)` is one event; backends realize it differently:

- **Stream** appends `&nbsp;` verbatim (`entity.text`).
- **JVM DOM** appends an `EntityReference` node (`createEntityReference`).
- **JS / WasmJS DOM** has no `createEntityReference` in browsers, so
  `JSDOMBuilder` parses `entity.text` through a temporary `innerHTML` and
  appends the resulting **text node** (`src/jsMain/kotlin/dom-js.kt`).

The DOM tests therefore assert node type/name instead of a serialized form,
and no test compares bytes across backends.

## `unsafe` scoping

`unsafe { ... }` is exactly one `onTagContentUnsafe` event. The stream backend
appends the block's raw string verbatim; JVM DOM parses it as an XML fragment
and appends the resulting nodes; JS/WasmJS appends it via `innerHTML`.
Escaping is only bypassed *inside* the block: the next regular `+"..."` is a
normal `content` event and is escaped again. Verified by
`TagConsumerTourTest`: `unsafe bypasses escaping only inside its own block` (stream) and by
`afterUnsafe` being a `TEXT_NODE` while the unsafe `<em>` became a real element
(DOM). The `<b>escaped again</b>` text never becomes a `<b>` element
(`getElementsByTagName("b").length == 0`).

## Consumer wrappers

Wrappers are ordinary `TagConsumer`s that sit between the DSL and the backend;
each one sees and may rewrite the event stream.

- **`DelayedConsumer`** (`delayed-consumer.kt`, used by `appendHTML`/`createHTML`)
  holds back the most recent `onTagStart` until the next event. Attribute
  changes that arrive while the start tag is buffered are *not* forwarded as
  events — the downstream reads them from the tag's attribute map when the
  start tag is flushed. `TagConsumerTourTest`: `delayed consumer folds attribute changes into the delayed tag start`
  records what `HTMLStreamBuilder` actually sees: no `attr(...)` events, and
  `start(div, data-raw=..., class=..., contenteditable=...)` carrying the
  folded attributes. Corollary: on the stream path, changing an attribute
  *after* content of the same tag was emitted throws
  `IllegalStateException` — use the DOM backend if you need late mutation.
- **`filter { ... }`** (`filter-consumer.kt`) can PASS/SKIP/DROP subtrees;
  `TagConsumerTourTest`: `library filter wrapper drops a subtree from the event stream` shows a
  dropped `<span>` never reaching the stream.
- **`onFinalize` / `onFinalizeMap`** (`finalize-consumer.kt`) observe
  `finalize` and receive a `partial` flag that is `true` when tags are still
  open (e.g. after an exception) — used by `Node.append` to avoid attaching
  half-built trees.
- **`trace` / `measureTime`** (`trace-consumer.kt`, `measure-consumer.kt`) are
  ready-made examples of pass-through wrappers.
- **Custom wrappers**: `UppercaseContentConsumer` in
  `TagConsumerTourTest` (delegate with `by downstream`, override what you
  transform); `TagConsumerTourTest`: `custom consumer wrapper transforms events in flight`
  shows text uppercased in flight while attributes and structure pass through.

## Exception contract (as currently implemented)

`visitTag` has **no** `try/finally`. If a DSL block throws
(`throwingTourFixture` throws while `div` and `span` are open):

- no pending `onTagEnd` is emitted — the recorded sequence ends at
  `start(span)` (`TagConsumerTourTest`: `exception skips pending end tags and finalize`);
- `finalize` is **not** called;
- the stream backend keeps whatever was already written, and the start tag
  still buffered in `DelayedConsumer` is lost, so the partial output is
  `<div>before` — note the missing `<span>`
  (`TagConsumerTourTest`: `stream keeps partial output after exception and drops the unflushed start tag`);
- the JVM DOM backend has already created the (detached) elements; the
  recorded events are identical to the recording path
  (`TourDomTest`: `exception propagates through the dom backend without pending end events`).

This is a contract test, not a redesign: the behavior is pinned down so any
future change is a conscious, visible decision.

## Complexity notes

- Stream path: O(1) extra memory per event; `DelayedConsumer` buffers exactly
  one tag. `escapeAppend` is O(n) per string with a lookup table for the four
  escaped characters.
- DOM path: O(tree) memory; each event maps to one DOM operation.
- `DelegatingMap` copies the initial attribute map lazily on first mutation.
- `RecordingTagConsumer` is test-only: O(events) memory, and it executes an
  `unsafe` block twice (once to record, once forwarded).

## Compatibility notes

- Public behavior and supported platforms are unchanged; all additions are in
  test sources and documentation.
- `onTagEvent` (lambda event handlers) is only meaningful on JS/WasmJS DOM;
  the stream and JVM DOM backends throw `UnsupportedOperationException`.
- Attribute/event order: attribute *events* follow DSL execution order;
  serialized attribute order follows insertion order of the internal
  `LinkedHashMap`. Tests must not depend on JVM reflection or DOM attribute
  iteration order — the DOM tests address nodes by document position and use
  `getAttribute`/`getElementsByTagName` instead.
- No test relies on a browser, the network, wall-clock waits, or file-system
  traversal order; the common tests run on every target, the DOM tests are
  JVM-only.
