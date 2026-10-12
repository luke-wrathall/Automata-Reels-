# Automata Reels

An Instagram-Reels-style Android app that plays an endless vertical feed of 1D cellular automata.
Each reel grows generation by generation down the screen, then keeps scrolling.

## Using it

- **Swipe up/down** to move between reels.
- **Tap** to pause/resume, **double-tap** (or the heart) to like.
- **Elementary / Varied** tabs switch the rule space:
  - *Elementary*: the 256 two-colour, nearest-neighbour Wolfram rules.
  - *Varied*: 3–5 colour totalistic rules, wider neighbourhoods (radius 2–4), and full
    rule tables for 3 colours (r=1) and 2 colours (r=2).
- The heart in the top-right opens your liked automata; tap one to watch it again.
- Each reel shows its rule name/number, family, start type and measured interest score.

## Installing

`AutomataReels.apk` in this folder is a release build. Copy it to an Android 8.0+ phone and open
it (allow installing from unknown sources when asked), or with USB debugging on:

```
adb install -r AutomataReels.apk
```

## How "interesting" is decided

Nothing is hardcoded per rule. Every candidate reel is simulated off-screen (360 generations on its
actual width and starting row) and measured (`ca/Interest.kt`):

| Measure | What it captures |
|---|---|
| Compression ratio | Deflate size of the space-time diagram vs. a shuffled copy: ~0 for repetitive patterns, ~1 for noise. The middle band is rewarded. |
| Multi-scale structure | Share of variation surviving 2×2 coarse-graining past the finest scale. Noise averages away; nested triangles and gliders don't. |
| Input-entropy variance | How much the mix of rule-table lookups fluctuates over time, measured after the pattern has spread round the ring. High for interacting structures (Wuensche). |
| Damage spreading | How far flipping one starting cell spreads. Partial spread signals edge-of-chaos dynamics. |
| Death / cycle detection | Patterns that die or fall into a short cycle (including moving ones) early are heavily penalised. |

On the elementary rules this puts the known "complex" class-4 rules (110, 54, 137, 193, 124) in the
top 10 for random starts, and dead/static rules at the bottom. These are checked by unit tests.

### Feed selection (`feed/FeedEngine.kt`)

1. Choose a start type (random row 50%, single cell 35%, sparse 15%). Scores are only compared
   within one start type, so the feed keeps a steady mix of looks.
2. Draw a pool of random rules (Langton-λ sampling in Varied mode), score them in parallel, and draw
   more if nothing is good enough. Elementary scores are remembered per rule, so the space is learned.
3. Sample one of the top finalists, favouring higher scores, with a penalty for looking like the last
   few reels shown.

**Likes tune the feed.** Some picks explore around liked rules (mutations of their rule tables, with
the same start type), and any candidate that behaves like or is a close relative of a liked reel gets a
bonus. With one like, rules near the liked one appear about twice as often.

## Building and testing

Requires JDK 17 and the Android SDK (platform 35). On this machine both live in
`C:\Users\sensi\android-dev`:

```
set JAVA_HOME=C:\Users\sensi\android-dev\jdk17
gradlew assembleRelease            # APK in app/build/outputs/apk/release/
gradlew testDebugUnitTest          # 26 JVM tests: simulator, scorer, feed, codecs, timing
gradlew connectedDebugAndroidTest  # 7 on-device UI tests (needs emulator/phone)
```

Start the emulator that was set up for testing with:

```
C:\Users\sensi\android-dev\sdk\emulator\emulator.exe -avd reels -gpu host
```
