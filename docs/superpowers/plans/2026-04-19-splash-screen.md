# Splash Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a 480×300 splash image (logo + "Startar Alipsa Accounting...") as early as possible during application startup on all platforms.

**Architecture:** Declare `SplashScreen-Image: splash.png` in the JAR manifest. The JVM reads this before any class loading and displays the image immediately. The splash closes automatically when the first Swing window becomes visible. A Gradle task generates the PNG from the existing `logo128.png` resource using Java2D; the generated file is committed as a static resource.

**Tech Stack:** Groovy, Java2D (`BufferedImage`, `Graphics2D`), `javax.imageio.ImageIO`, Gradle task, JVM Splash Screen API (JAR manifest attribute), JUnit 6 + groovier-junit.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `app/build.gradle` | Add `generateSplashImage` task; add `SplashScreen-Image` manifest attribute |
| Create | `app/src/main/resources/splash.png` | Generated splash image (committed after generation) |
| Create | `app/src/test/groovy/unit/se/alipsa/accounting/ui/SplashScreenTest.groovy` | Verifies `splash.png` is on the classpath with correct dimensions |

---

### Task 1: Write the failing test

**Files:**
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/ui/SplashScreenTest.groovy`

- [ ] **Step 1: Create the test file**

```groovy
package unit.se.alipsa.accounting.ui

import org.junit.jupiter.api.Test

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

import static org.junit.jupiter.api.Assertions.*

final class SplashScreenTest {

  @Test
  void splashImageIsOnClasspath() {
    URL resource = getClass().getResource('/splash.png')
    assertNotNull(resource, 'splash.png must be bundled as a classpath resource')
  }

  @Test
  void splashImageHasCorrectDimensions() {
    InputStream stream = getClass().getResourceAsStream('/splash.png')
    assertNotNull(stream, 'splash.png must be readable from the classpath')
    BufferedImage image = ImageIO.read(stream)
    assertEquals(480, image.width, 'splash width must be 480 px')
    assertEquals(300, image.height, 'splash height must be 300 px')
  }
}
```

- [ ] **Step 2: Run the test — expect FAIL**

```bash
./gradlew :app:test --tests "unit.se.alipsa.accounting.ui.SplashScreenTest"
```

Expected: both tests fail with `AssertionError` because `splash.png` does not yet exist.

---

### Task 2: Generate `splash.png` and make the tests pass

**Files:**
- Modify: `app/build.gradle` — add `generateSplashImage` task
- Create: `app/src/main/resources/splash.png` — output of the Gradle task

- [ ] **Step 1: Add `generateSplashImage` task to `app/build.gradle`**

Insert the following block after the existing `generateReleaseMetadata` task (anywhere in the file is fine, but after the existing task registrations keeps it tidy):

```groovy
tasks.register('generateSplashImage') {
  group = 'build'
  description = 'Generates splash.png from the application logo using Java2D'
  outputs.file(layout.projectDirectory.file('src/main/resources/splash.png'))
  doLast {
    System.setProperty('java.awt.headless', 'true')

    File logoFile = layout.projectDirectory.file('src/main/resources/icons/logo128.png').asFile
    if (!logoFile.exists()) {
      throw new GradleException("Logo not found: ${logoFile.absolutePath}")
    }

    java.awt.image.BufferedImage logo = javax.imageio.ImageIO.read(logoFile)

    int width = 480
    int height = 300
    java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
        width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    java.awt.Graphics2D g = canvas.createGraphics()
    try {
      g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
          java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
          java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

      g.color = java.awt.Color.WHITE
      g.fillRect(0, 0, width, height)

      int logoX = (width - logo.width).intdiv(2)
      int logoY = 50
      g.drawImage(logo, logoX, logoY, null)

      g.color = new java.awt.Color(60, 60, 60)
      g.font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 18)
      String text = 'Startar Alipsa Accounting...'
      java.awt.FontMetrics fm = g.getFontMetrics()
      int textX = (width - fm.stringWidth(text)).intdiv(2)
      int textY = logoY + logo.height + 50
      g.drawString(text, textX, textY)
    } finally {
      g.dispose()
    }

    File outputFile = layout.projectDirectory.file('src/main/resources/splash.png').asFile
    outputFile.parentFile.mkdirs()
    javax.imageio.ImageIO.write(canvas, 'PNG', outputFile)
    println "Generated: ${outputFile.absolutePath} (${width}x${height})"
  }
}
```

- [ ] **Step 2: Run the generator**

```bash
./gradlew :app:generateSplashImage
```

Expected output contains:
```
Generated: .../app/src/main/resources/splash.png (480x300)
```

- [ ] **Step 3: Confirm the file was created**

```bash
ls -lh app/src/main/resources/splash.png
```

Expected: file exists, size roughly 5–30 KB.

- [ ] **Step 4: Run the tests — expect PASS**

```bash
./gradlew :app:test --tests "unit.se.alipsa.accounting.ui.SplashScreenTest"
```

Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/resources/splash.png app/src/test/groovy/unit/se/alipsa/accounting/ui/SplashScreenTest.groovy
git commit -m "generera och lägg till startskärmsbild"
```

---

### Task 3: Wire the JAR manifest attribute

**Files:**
- Modify: `app/build.gradle` — add `SplashScreen-Image` attribute to the `jar` manifest

- [ ] **Step 1: Add the manifest attribute**

Find the existing `tasks.named('jar', Jar)` block in `app/build.gradle` (currently lines ~154–163) and add `'SplashScreen-Image': 'splash.png'` as a new attribute:

```groovy
tasks.named('jar', Jar) {
  manifest {
    attributes(
        'Implementation-Title': releaseConfig.displayName,
        'Implementation-Version': releaseConfig.version,
        'Implementation-Vendor': releaseConfig.vendor,
        'Main-Class': releaseConfig.mainClassName.get(),
        'SplashScreen-Image': 'splash.png'
    )
  }
}
```

- [ ] **Step 2: Build the JAR and verify the manifest**

```bash
./gradlew :app:jar && unzip -p app/build/libs/app-*.jar META-INF/MANIFEST.MF
```

Expected output includes:
```
SplashScreen-Image: splash.png
```

- [ ] **Step 3: Verify `splash.png` is in the JAR**

```bash
unzip -l app/build/libs/app-*.jar | grep splash
```

Expected:
```
        ...   splash.png
```

- [ ] **Step 4: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle
git commit -m "lägg till SplashScreen-Image i JAR-manifestet"
```

---

## Verification

After both tasks are committed, the splash screen is active in any JAR-launched context. To confirm visually on the current platform:

```bash
./gradlew :app:installDist
```

Then launch the installed binary directly (not via `gradlew run`, which bypasses the JAR manifest):

- **Linux:** `app/build/install/app/bin/app`
- **Windows:** `app\build\install\app\bin\app.bat`

The splash image should appear immediately on launch, before the main window opens.
