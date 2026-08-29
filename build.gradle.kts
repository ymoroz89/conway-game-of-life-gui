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

// Compose compiler diagnostics. Opt-in, because they force a full recompile and are only
// wanted when a recomposition question is actually being asked:
//
//   ./gradlew compileKotlin --rerun-tasks -PcomposeReports
//
// The reports say, per composable, whether it is skippable and whether each parameter is
// stable -- which is what decides how much work a state change actually causes. That is a
// static answer, and cheaper than instrumenting a running UI to count recompositions.
if (project.hasProperty("composeReports")) {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose-reports")
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
    }
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

// Headless measurement of the two per-frame costs on the render path. Usage:
//   ./gradlew renderBench
//   ./gradlew renderBench --args="201 801"
tasks.register<JavaExec>("renderBench") {
    group = "verification"
    description = "Runs the headless render-path (snapshot equality and repaint) benchmark."
    mainClass.set("com.conway.bench.RenderPathBenchmarkKt")
    classpath = mainRuntimeClasspath
}

// Forward -Dconway.* (and -Dconway.log.*) properties from the Gradle command line to the
// application JVM, so `./gradlew run -Dconway.gridSize=401` configures the app the same
// way it always has. Applies to every JavaExec task, including `run` and `bench`.
tasks.withType<JavaExec>().configureEach {
    System.getProperties()
        .filterKeys { (it as String).startsWith("conway.") }
        .forEach { (key, value) -> systemProperty(key as String, value as String) }

    // Flight Recorder, opt-in. Gradle's own -Dorg.gradle.jvmargs configures the daemon, not
    // the forked JVM, so the recording has to be attached to the task itself:
    //
    //   ./gradlew bench -Pjfr
    //   ./gradlew run -Pjfr=duration=30s
    //
    // The value is passed to StartFlightRecording verbatim, so any of its options work.
    // Recordings land in build/jfr/<task>.jfr; read them with `jfr summary` or JMC.
    if (project.hasProperty("jfr")) {
        val options = (project.property("jfr") as String).ifBlank { "duration=30s" }
        val recording = layout.buildDirectory.file("jfr/$name.jfr").get().asFile
        doFirst { recording.parentFile.mkdirs() }
        // An argument provider rather than jvmArgs(...): the Compose plugin assigns the
        // `run` task's jvmArgs from its own application block, which replaces anything set
        // here. Providers are additive and resolved at execution time, so they survive it.
        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-XX:StartFlightRecording=$options," +
                        "filename=${recording.absolutePath},settings=profile",
                )
            },
        )
    }
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