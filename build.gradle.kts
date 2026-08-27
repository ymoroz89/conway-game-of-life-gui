import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "com.conway"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

val composeVersion = "1.12.0"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    // The installed JDK (26) compiles down to the 25 bytecode target; the Compose Gradle
    // plugin also contributes Java sources, and the two upstream tasks must agree.
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // Compose Multiplatform for Desktop. `currentOs` pulls the host-specific Skia
    // natives (skiko), which is what actually draws the board; without it the app
    // fails at startup with "Cannot find libskiko-<host>.dylib.sha256". The UI is
    // built from the ui/foundation primitives rather than the optional Material
    // widgets.
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.runtime:runtime:${composeVersion}")
    implementation("org.jetbrains.compose.foundation:foundation:${composeVersion}")
    implementation("org.jetbrains.compose.ui:ui:${composeVersion}")
    implementation("org.jetbrains.compose.ui:ui-graphics:${composeVersion}")

    // Logging, unchanged from the Swing-era build.
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Engine tests are headless; nothing here needs a display or the Compose toolkit.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val mainRuntimeClasspath = sourceSets["main"].runtimeClasspath

// Headless throughput comparison across partition counts. Usage:
//   ./gradlew bench
//   ./gradlew bench --args="401 300 virtual"
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the headless partition-count throughput benchmark."
    mainClass.set("com.conway.bench.GenerationBenchmarkKt")
    classpath = mainRuntimeClasspath
}

// Forward -Dconway.* (and -Dconway.log.*) properties from the Gradle command line to the
// application JVM, so `./gradlew run -Dconway.gridSize=401` configures the app the same
// way it always has. Applies to every JavaExec task, including `run` and `bench`.
tasks.withType<JavaExec>().configureEach {
    System.getProperties()
        .filterKeys { (it as String).startsWith("conway.") }
        .forEach { (key, value) -> systemProperty(key as String, value as String) }
}


compose.desktop {
    application {
        mainClass = "com.conway.MainKt"

        // skiko loads its Skia natives with System::load, which JDK 25+ reports as a
        // restricted method and a future JDK will block outright. Granting native access
        // to the application's own code silences the warning today and keeps the app
        // working on later JDKs. Applies to `run` and to the packaged launcher.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "conway-game-of-life"
            packageVersion = "1.0.0"
        }
    }
}