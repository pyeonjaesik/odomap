# Odomap — 달린 길이 지도가 된다

Hyundai Pleos Connect (Android Automotive) contest app: record the roads driven, count first-time roads, draw roads thicker the more often they are
driven. The core algorithm is a faithful Kotlin port of the "발자취" (explore) computation from the phone app; car-specific tuning comes later.

## Layout

| Module | What |
| --- | --- |
| `:core` | Pure Kotlin/JVM (no `android.*`). Package `com.odomap.core` — `geo` (distance, JS-exact `hypot`/`round`), `run` (Kalman + RTS path smoother, Doppler check), `explore` (road net, on-foot classifier, pipeline, z14 tiles / drawn lines). JUnit tests + golden parity fixtures. |
| `:app` | Android app `com.odomap.app` (minSdk 28, compile/target 35, Compose + MapLibre). Location sources (Pleos FusedLocation → Android fallback, demo replay from `assets/drives/`), drive state (AAOS gear → Pleos gear → speed rule), recording foreground service + `DriveRecorder` (ExploreRecorder on the cumulative `CarProfile` graph), on-device storage (z14 tile JSON + drive folders), paper-style map, home / live / driving-restricted / drive list & detail / arrival / demo panel (long-press the logo), TTS. Depends on `:core` and the Pleos Playground SDKs (FusedLocation 1.1.3, Vehicle 2.3.2, NaviHelper 3.0.5 from `nexus-playground.pleos.ai`). |
| `tools/` | Golden-fixture generator: runs the original TypeScript (verbatim copies in `tools/ts/`) with node and writes `core/src/test/resources/golden/*.json`. |

Toolchain: Gradle 8.10.2 (wrapper), AGP 8.8.2, Kotlin 2.0.21, JDK 17+ (Android Studio's bundled JBR 21 works), Java/Kotlin bytecode target 17.

## Build & test

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # once
./gradlew :core:test :app:assembleDebug
```

App JVM tests: `./gradlew :app:testDebugUnitTest -PodomapRenderDir=/tmp/odomap` replays demo drives through the recorder and writes PNGs
(engine renders + Robolectric screenshots of the Compose screens, map drawn by `CanvasMap` since MapLibre's GL renderer cannot run on the JVM).

The golden parity summary is printed by `GoldenParityTest` (`[golden] N fixtures, … not bit-identical …`).

## Regenerating the golden fixtures

```sh
tools/sync-ts.sh --check        # are tools/ts/* still identical to the phone app's sources? (tools/sync-ts.sh copies them again)
cd tools && npm install && npm run golden
```

## Tuning hooks (for the car rule set)

- `ExploreRules` (net: step / snap / band / pass rules) — passed to `ExploreGraph(rules = …)`.
- `OnFootRules` and `ExplorePipelineConfig(runFilter, maxSpeedMps, onFootRules, onFootFilter)` — passed to `computeExploreWalk` / `ExploreRecorder`.
  `onFootFilter = false` stops dropping non-walking stretches (needed for driving).
- `RunFilter` (Kalman filter constants).
All defaults are the phone app's values, so the defaults reproduce the original bit for bit.
