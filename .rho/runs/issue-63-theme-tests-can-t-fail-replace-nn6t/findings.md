# Task 7 — the gate and the sweep: what was checked and what was found

## The gate

`bash .rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/verify.sh`, run once on the tree as it
stands after the sweep fix below (gitignored copy of the output: `app/build/scratch63/task7-verify.log`):

- attempt 1 green: `ok gate: :app:assembleDebug :app:testDebugUnitTest` — no retry, no flake;
- `771 test names, 92 classes indexed`;
- all 10 named cases `ok`, all 14 whole-class checks `ok` — including `LiftoffShellTest`,
  `PreviewsTest` and the four rewritten classes;
- `=== 25 ok, 0 failed ===` and `=== VERDICT: done ===`;
- the reports the gate produced: 92 classes, 771 testcases, no `<failure>`, `<error>` or `<skipped>`
  anywhere; no `com.liftoff.app.MainActivityTest` report, because task 6 deleted that file.

## The sweep: what was read

119 assertion calls across the four rewritten classes — `StripesTest` 7, `PatternTrackTest` 45,
`ButtonsTest` 37 (`LiftoffIcons.check()` at 182 is a call, not an assertion, so it is not counted),
`OffsetShadowTest` 30. Every call's operands were traced back to one of:

1. a production constant or function that the draw site itself reads — `TriStripeBandHeightDp`,
   `chipSizeDp`, `ButtonShape`, `OffsetShadowDefaultOffsetDp`, `LiftoffIcons.check()`;
2. a value measured from a rendered node — `nodeSizes`, `nodePositions`, `size`, `position`,
   `assertExists` / `assertDoesNotExist`;
3. a literal taken from `design/README.md`, or a hex colour literal.

One call had an operand that was neither: the round trip below.

## Found in the four classes, and fixed (1)

`OffsetShadowTest.offsetShadowDefaultsAreFourDp`, the corner block task 5 wrote (was lines 74-78):

```kotlin
val shadowCorner = cornerOf(RoundedCornerShape(corner), probe, Density(1f))
assertEquals("the corner offsetShadow fills is the corner the constant names", 4.0f, shadowCorner, 0.001f)
```

`corner` is `OffsetShadowDefaultCornerRadiusDp`. The test built the `RoundedCornerShape` itself and
read the radius back off the outline that shape hands to `createOutline`. At `Density(1f)` with a
120×60 probe that round trip is the identity — 4.dp in, 4.0f out, unclamped because 4 < 60/2 — so
the assertion compared the literal `4.0f` against a copy of the constant. The only production change
that could reach it was the constant itself, which the line above it (`assertEquals(4.dp, corner)`)
already catches; what it actually measured was Compose's `RoundedCornerShape`. The companion
`assertEquals(buttonCorner, shadowCorner)` was real, but took its right-hand side from that round trip.

Both corner assertions now read `ButtonShape` (`Shape.kt:8`) — the shape `Buttons.kt` fills its
buttons with (60, 123), and the shape the shadow sits behind: `buttonCorner` pinned against the
design's 4 dp, and against `OffsetShadowDefaultCornerRadiusDp.toPx()` at the same `Density(1f)` that
`cornerOf` measures at. The `RoundedCornerShape` import went with the round trip.

Proof (gitignored `app/build/scratch63/probe-g.sh`, log `probe-g.log`): `ButtonShape` 4.dp → 8.dp in
`Shape.kt`, nothing in the test touched, `OffsetShadowTest` run alone — `offsetShadowDefaultsAreFourDp`
fails with `the corner a button is filled with is the design's 4 dp expected:<4.0> but was:<8.0>`. The
assertion the sweep removed never mentioned `ButtonShape` or `Shape.kt`, so no change to
production's shapes could have reached it. Task 5's probe B, recorded in
`app/build/scratch63/probe-out.txt`, shows a change to the constant itself is caught at its pin.

## Found outside the four classes, and fixed (1)

`PreviewsTest.kt:26` — `check(decl.startsWith("private fun") || decl.startsWith("private suspend"))`
sat inside the `if` whose condition it repeated verbatim, so it could not fail. Task 6's task text
asked for `assert(...)` → `check(...)`, which kept it; `plan.md`'s Files section asked for this one to
be deleted. It is deleted. The real checks are `check(found)` — a `@Preview` whose declaration is not
private never matches the loop, so `found` stays false and the check names the file and line — and
`assertEquals(9, previewCount)`. `PreviewsTest` still runs and still passes, 1 case.

## Checked and left as it is

- `StripesTest` 46/47 (`18.dp == band × 3`, `10.dp == band × 2`) restate the 6.dp / 5.dp pins, and
  `OffsetShadowTest` 172-175 (`plain.width == painted.width`, …) restate the pins at 164-169. They are
  redundant, not vacuous: each fails when the value or the drawing changes.
- `PatternTrackTest` 143 (`upcomingRing == connector`) and 145-150 are relations between constants that
  are each pinned to a literal elsewhere in the same case — real, and task 5's probes B and C showed
  them biting.
- Colour assertions (`Color(0xFF1D1B19) == Ink`, …) repeat `ColorTokensTest`'s pins. A hex literal
  against a token is not a copy of itself.
- `assertTrue(offset > 0.dp)` / `assertTrue(corner > 0.dp)` are weak — any positive value passes — but
  they can fail, so they are not sweep findings.
- `FontLicenseTest` still calls Kotlin `assert()` 4 times. It is not one of the four classes, and
  `plan.md`'s Decision records Claude's review choosing to leave it; Gradle's `Test` task sets
  `enableAssertions(true)` (map.md), so those asserts are not silent.

## map.md lines that do not match the tree, noticed while sweeping (map.md not edited)

- line 26: `ButtonShape = CornerBasedShape(16,16,16,16,0,0)` — `Shape.kt:8` is
  `val ButtonShape: Shape = RoundedCornerShape(4.dp)`.
- line 44: `MainActivityTest.kt` (64 lines, 2 cases) with a local `mutableStateOf` list — it was 55
  lines and had no `mutableStateOf`; task 6 deleted it.
- line 58: the no-op `private fun Modifier.offset(offset: Dp): Modifier = this` is at
  `OffsetShadow.kt:87`, not 83.
- line 45: `PreviewsTest`'s `assert(found)` was at 34, not 32, and the scan is
  `File("src/main/java/com/liftoff/app/ui/theme")` relative to the `app/` project directory (Gradle's
  test working directory), not a walk up to `settings.gradle.kts`.

Earlier tasks already recorded the phantom colour tokens (`InkRed`, and the wrong Teal / TealLight /
Muted) in map.md's own appended sections at 327-328 and 385-387.
