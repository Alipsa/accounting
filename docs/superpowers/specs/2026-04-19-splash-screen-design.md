# Splash Screen Design

**Date:** 2026-04-19
**Status:** Approved

## Problem

On Windows the application takes ~15 seconds to start. Users see nothing during this time and may believe the app has failed to launch.

## Solution

Use the JVM's built-in splash screen mechanism (`SplashScreen-Image` in the JAR manifest). The JVM reads the manifest before any class loading and displays the image immediately when the process starts — the earliest possible moment. The splash closes automatically when the first Swing window becomes visible.

## Approach

**JAR manifest** (not `--java-options`). Adding `SplashScreen-Image: splash.png` to the manifest is the standard approach and requires no packaging changes. The JVM resolves the path from the classpath.

## Components

### 1. Splash image — `app/src/main/resources/splash.png`

- Format: PNG, 480 × 300 px
- Background: white
- Content: `logo128.png` centered in the upper portion, "Startar Alipsa Accounting..." in dark gray below
- Created by a standalone generation script (`scripts/generate-splash.groovy`) using Java2D/ImageIO, then committed as a static resource
- The script is committed alongside the image so it can be re-run if the logo or text changes

### 2. JAR manifest — `app/build.gradle`

Add to the existing `tasks.named('jar', Jar)` manifest block:

```groovy
'SplashScreen-Image': 'splash.png'
```

### 3. No code changes

`AlipsaAccounting.groovy` and `MainFrame.groovy` require no modifications. The JVM closes the splash automatically when `frame.visible = true` is set in `MainFrame.display()`.

## Out of Scope

- Progress text updates during startup (static image only)
- Splash during `./gradlew run` (classpath mode does not use the JAR manifest splash; expected and acceptable)
- macOS / Linux — the splash will also appear there but the primary motivation is Windows

## Edge Cases

- If `showStartupVerificationWarning` fires (shows a dialog before the main frame), the splash closes at the dialog instead. This is acceptable since all slow initialization is already complete at that point.
- If `logo128.png` is missing from the classpath at generation time, the script must fail with a clear error rather than producing a blank image.
