# Consumer Events Tour

An executable tour of how a kotlinx.html DSL call travels from tag classes and
attribute delegates, through the `TagConsumer` event pipeline, to the three
terminal backends (stream, JVM DOM, JS/Wasm DOM).

Every event sequence quoted in this document is asserted by a test; nothing
here is inferred from final HTML strings. Run the tour with:

```
./gradlew jvmTest --tests "kotlinx.html.tests.tour.*" --tests "kotlinx.html.tests.DomTourTest"
```

| Tour piece | Location |
|---|---|
| Shared DSL fixtures (`tourFixture`, `tourExceptionFixture`) | `src/commonTest/kotlin/tour/TourFixtures.kt` |
| Recording consumer (test-only terminal consumer) | `src/commonTest/kotlin/tour/RecordingConsumer.kt` |
| Golden event sequence | `src/commonTest/kotlin/tour/RecordingConsumerTourTest.kt` |
| Stream serialization and escaping | `src/commonTest/kotlin/tour/StreamTourTest.kt` |
| Consumer wrappers | `src/commonTest/kotlin/tour/ConsumerWrapperTourTest.kt` |
| Exception contract | `src/commonTest/kotlin/tour/ExceptionTourTest.kt` |
| JVM DOM backend | `src/jvmTest/kotlin/dom-tour.kt` |

## The four layers

The single most important mental model: the same characters exist in four
distinct representations, and only one of them is escaped.

| # | Layer | Where | Example for `+"Fish & Chips"` |
|---|---|---|---|
| 1 | DSL raw text | your Kotlin source / `Tag.text` (`src/commonMain/kotlin/api.kt`) | `Fish & Chips` |
| 2 | Consumer event content | `TagConsumer.onTagContent` (`src/commonMain/kotlin/api.kt`) | `Fish & Chips` — still raw |
| 3 | Stream serialization | `HTMLStreamBuilder` (`src/commonMain/kotlin/stream.kt`) | `Fish &amp; Chips` |
| 4 | DOM text node | `HTMLDOMBuilder` (JVM, `src/jvmMain/kotlin/dom-jvm.kt`), `JSDOMBuilder` (JS, `src/jsMain/kotlin/dom-js.kt`) | `Fish & Chips` — raw again |

Never infer layer-2 events from layer-3 output: escaping happens *inside* the
stream consumer, and the DOM consumer never escapes at all (its serializer
re-escapes later, with different rules — see "Platform differences").

## Event pipeline

```
div { id = "x"; +"text" }
   │  (generated entry points: src/commonMain/kotlin/generated/gen-consumer-tags.kt)
   ▼
visitTag / visitTagAndFinalize        src/commonMain/kotlin/visit.kt
   │  onTagStart → block() → onTagEnd → finalize()
   ▼
attribute writes: Tag.attributes = DelegatingMap
   │  src/commonMain/kotlin/delegating-map.kt — every put/remove that changes a
   │  value fires onTagAttributeChange(tag, key, value| null)
   ▼
consumer wrappers (optional, composable)
   │  delayed()        src/commonMain/kotlin/delayed-consumer.kt
   │  filter { }       src/commonMain/kotlin/filter-consumer.kt
   │  onFinalize(Map)  src/commonMain/kotlin/finalize-consumer.kt
   │  measureTime()    src/commonMain/kotlin/measure-consumer.kt
   │  trace { }        src/commonMain/kotlin/trace-consumer.kt
   ▼
terminal consumer
      HTMLStreamBuilder  src/commonMain/kotlin/stream.kt        (JVM/JS/Wasm/Native)
      HTMLDOMBuilder     src/jvmMain/kotlin/dom-jvm.kt          (JVM)
      JSDOMBuilder       src/jsMain/kotlin/dom-js.kt            (JS, WasmJs variant in src/wasmJsMain)
      RecordingConsumer  src/commonTest/kotlin/tour/RecordingConsumer.kt (tests)
```

### What each event means

| Callback | Fired when | Notes |
|---|---|---|
| `onTagStart(tag)` | `visitTag` enters a tag | `tag.attributes` is live; backends snapshot it here |
| `onTagAttributeChange(tag, key, value)` | any attribute write/remove through `DelegatingMap` | `value == null` means removal; only fired when the value actually changes |
| `onTagContent(content)` | `+"text"` / `text(...)` | raw, unescaped; an empty string is still an event |
| `onTagContentEntity(entity)` | `+Entities.copy` / `entity(...)` | backends must not escape `entity.text` |
| `onTagContentUnsafe(block)` | `unsafe { ... }` | bypasses escaping for this block only |
| `onTagComment(content)` | `comment("...")` | |
| `onTagEnd(tag)` | `visitTag` leaves a tag | not fired if the block throws (see Exception contract) |
| `finalize()` | `visitAndFinalize` after the root tag ends | wrappers may transform the result (`onFinalizeMap`) |

Attribute delegates (`src/commonMain/kotlin/attributes.kt`, generated
`gen-attributes.kt` / `gen-attr-traits.kt`) encode typed values to strings
before the map write: `BooleanAttribute`/`TickerAttribute` (`disabled=true` →
`disabled="disabled"`, `false` removes the attribute), `EnumAttribute`
(`ButtonType.submit` → `"submit"` via `AttributeEnum.realValue`),
`StringSetAttribute` (`classes = setOf("alpha", "beta")` → `"alpha beta"`).

## Golden sequence

`tourFixture()` (see `TourFixtures.kt`) covers: plain/boolean/enum attributes,
a class set, an attribute value needing escapes, nested tags, an empty tag,
special characters, an empty text, an entity, an unsafe block, and ordinary
text after the unsafe block.

Recording path (`RecordingConsumerTourTest` asserts exactly this):

```
TagStart("div", {})                                   ← attributes empty at start
AttributeChange("div", "id", "tour-root")
AttributeChange("div", "class", "alpha beta")
AttributeChange("div", "data-raw", "5 < 6 & \"ok\"")  ← raw at this layer
TagStart("input", {})
AttributeChange("input", "disabled", "disabled")
TagEnd("input")                                       ← empty tag still ends
TagStart("button", {})
AttributeChange("button", "type", "submit")
Content("Go")
TagEnd("button")
TagStart("span", {})
Content("Fish & Chips <3 \"quoted\"")                 ← unescaped
Content("")                                           ← empty text is an event
ContentEntity("copy", "&copy;")
ContentUnsafe("<b>raw</b>")
Content("still <escaped> & safe")                     ← unsafe did not leak
TagEnd("span")
TagEnd("div")
Finalize
```

Stream path (`StreamTourTest`, `createHTML(prettyPrint = false)`):

```html
<div id="tour-root" class="alpha beta" data-raw="5 &lt; 6 &amp; &quot;ok&quot;"><input disabled="disabled"><button type="submit">Go</button><span>Fish &amp; Chips &lt;3 &quot;quoted&quot;&copy;<b>raw</b>still &lt;escaped&gt; &amp; safe</span></div>
```

DOM path (`DomTourTest`): the same fixture produces a `div` element whose
`getAttribute("data-raw")` is the raw string `5 < 6 & "ok"`, whose `span` has
five children — text, empty text, entity reference, `<b>` element, text.

## Escaping rules

Verified separately for attribute values and text content
(`StreamTourTest.attribute values and text content ...`):

- Both use the same `escapeMap` in `src/commonMain/kotlin/stream.kt`:
  `<` → `&lt;`, `>` → `&gt;`, `&` → `&amp;`, `"` → `&quot;`.
- Attribute *names* are validated at serialization time
  (`isValidXmlAttributeName`): empty names, names starting with `xml`, and
  names containing whitespace, `/`, `>`, `"`, `'`, `=` are rejected with
  `IllegalArgumentException`.
- `\&` is an escape hatch in `escapeAppend`: `\&copy;` in DSL text serializes
  to a literal `&copy;` without an entity event. A trailing lone `\` fails the
  `check` in `escapeAppend`.
- `onTagContentEntity` output is appended verbatim — entities are never
  re-escaped (`Entities.amp` → `&amp;`, not `&amp;amp;`).
- `unsafe { }` output is appended verbatim.

### Unsafe is scoped

`unsafe` bypasses escaping only for the events emitted inside its own block.
Content emitted after the block goes through `onTagContent` and is escaped
normally — asserted by `StreamTourTest.unsafe bypass is scoped to its own
block only` and by the last `Content("still <escaped> & safe")` event in the
golden sequence.

## Exception contract

Pinned by `ExceptionTourTest` using `tourExceptionFixture()`, whose DSL block
throws mid-tree. Current behavior (do not read this as a guarantee — it is a
record of what the code does today):

- `visitTag` (`src/commonMain/kotlin/visit.kt`) has no `try/finally`: **no
  `onTagEnd` is emitted** for the throwing tag or any of its ancestors.
- The consumer remains usable; `finalize()` can still be called.
- `FinalizeConsumer` counts unbalanced start/end events and reports
  `partial = true` to `onFinalize`/`onFinalizeMap` blocks.
- The stream backend keeps whatever bytes were flushed before the throw
  (`<div>before<span>doomed`) and synthesizes no end tags.
- The JVM DOM backend instead throws `IllegalStateException` from
  `finalize()`, because `lastLeaved` is only assigned in `onTagEnd`
  (`src/jvmMain/kotlin/dom-jvm.kt`). This is a real platform difference, not
  a bug the tests paper over.

## Consumer wrappers

Wrappers are ordinary `TagConsumer`s that sit between the DSL and the
downstream consumer; events flow through them one callback at a time
(`ConsumerWrapperTourTest`):

- `delayed()` holds back `onTagStart` until the first content/end event, so
  attribute writes inside the tag block are folded into the start tag.
  Downstream of it there are **no** `onTagAttributeChange` events — the
  changes are already visible in `tag.attributes` when the start fires. This
  is what makes the single-pass `HTMLStreamBuilder` usable, which is why
  `createHTML()`/`appendHTML()` wrap themselves in `delayed()`.
- `onFinalizeMap` transforms the `finalize()` result (e.g. `StringBuilder` →
  `String` in `createHTML`).
- A custom wrapper (the test's `ShoutContentConsumer`) can rewrite one event
  type and delegate the rest via `TagConsumer<R> by downstream`.
- `filter { }` drops/skips whole subtrees; `measureTime()` and `trace { }`
  add timing and logging without changing events.

## Platform differences

Documented, asserted where testable, never smoothed over:

- **Entity nodes**: JVM DOM uses `document.createEntityReference(name)`;
  browsers dropped that API, so `JSDOMBuilder` decodes the entity through a
  scratch `span.innerHTML` and inserts a text node instead. A JDK
  serialization of the JVM tree may drop the unresolved entity reference
  entirely.
- **Unsafe content**: the stream backend appends it raw; JVM DOM parses it as
  **XML** (it must be well-formed — the shared fixture uses `<b>raw</b>` for
  this reason); JS DOM appends via `innerHTML +=` (HTML parsing, lenient).
- **Serialization escaping**: `HTMLStreamBuilder` escapes `<`, `>`, `&`, `"`
  everywhere. The JDK transformer used by `Element.serialize` (HTML output
  method) escapes `&` and `"` but not `<` in attribute values, and `<`, `>`,
  `&` in text. Same tree, different bytes — by design of the JDK serializer.
- **Attribute order**: `DelegatingMap` is insertion-ordered in the current
  implementation, but the tour tests never rely on it — DOM assertions use
  `getAttribute(name)`, and the recording consumer snapshots attributes into
  a map compared order-insensitively.

## Complexity and compatibility notes

- `DelegatingMap` is copy-on-write: attribute maps start as immutable
  singletons/empty maps and are only copied to a `LinkedHashMap` on first
  mutation — O(1) and allocation-free for tags whose attributes never change
  after construction.
- `DelayedConsumer` buffers exactly one tag — O(1) memory regardless of tree
  size; `HTMLStreamBuilder` is a single O(n) pass over the output.
- `FinalizeConsumer` keeps an integer level counter — O(1).
- `FilterTagConsumer` tracks skipped/dropped levels in a `HashSet`/nullable
  level — O(depth).
- `TagConsumer.head` is `@ExperimentalKotlinxHtmlApi`; the rest of the
  consumer interface is stable API guarded by binary-compatibility-validator
  (`api/` dumps), so event semantics cannot change silently.
- The recording consumer lives in `commonTest`, so the same expectations run
  on JVM, JS, Wasm and Native test tasks without any browser-external
  dependency, clock, or filesystem assumptions.
