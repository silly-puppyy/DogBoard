/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlinx.kover)
}

val projectVersionName = providers.gradleProperty("projectVersionName").get()
val projectVersionNameSuffix = projectVersionName.substringAfter("-", "").let { suffix ->
    if (suffix.isNotEmpty()) {
        "-$suffix"
    } else {
        suffix
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-jvm-default=enable",
            "-Xwhen-guards",
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
            "-XXLanguage:+LocalTypeAliases",
        ))
    }
}

configure<ApplicationExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = providers.gradleProperty("projectCompileSdk").get().toInt()
    buildToolsVersion = tools.versions.buildTools.get()
    ndkVersion = tools.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "io.github.sillypuppyy.dogboard"
        minSdk = providers.gradleProperty("projectMinSdk").get().toInt()
        targetSdk = providers.gradleProperty("projectTargetSdk").get().toInt()
        versionCode = providers.gradleProperty("projectVersionCode").get().toInt()
        versionName = projectVersionName.substringBefore("-")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${getGitCommitHash().get()}\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v~draft2\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")

        sourceSets {
            maybeCreate("main").apply {
                assets.directories += "src/main/assets"
                // DogBoard: the settings search index, generated from the settings pages (generateSettingsIndex)
                kotlin.directories += "build/generated/settingsIndex"
            }
        }
    }

    bundle {
        language {
            // We disable language split because FlorisBoard does not use
            // runtime Google Play Service APIs and thus cannot dynamically
            // request to download the language resources for a specific locale.
            enableSplit = false
        }
    }

    // DogBoard: the trigram model is memory-mapped straight from the APK, so it must be stored uncompressed
    androidResources {
        noCompress += "bin"
        noCompress += "tflite" // the neural model, memory-mapped from the package
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug+${getGitCommitHash(short = true).get()}"

            isDebuggable = true
            isJniDebuggable = false
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        named("release") {
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        create("benchmark") {
            initWith(getByName("release"))

            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench+${getGitCommitHash(short = true).get()}"

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        baseline = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.useJUnitPlatform()
            // DOGBOARD_JFR=<file>: record the test JVM with Java Flight Recorder (allocation profiling)
            System.getenv("DOGBOARD_JFR")?.let { f -> it.jvmArgs("-XX:StartFlightRecording=filename=$f,settings=profile") }
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("src/main/config")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

tasks.withType<Test> {
    // DogBoard: DOGBOARD_JFR=<file.jfr> records the test JVM with Java Flight Recorder (allocation profiling)
    System.getenv("DOGBOARD_JFR")?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    // DOGBOARD_TEST_HEAP=3g: a bigger test heap for large languages' evals (Russian: 1.4M words; the default 512 MB ran out)
    System.getenv("DOGBOARD_TEST_HEAP")?.let { maxHeapSize = it }
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()
}

kover {
    useJacoco()
}

// DogBoard: a release ships the word-data snapshots of its packs' current word lists (SnapshotExportTest, not in git);
// without them a first start parses the word lists, which 2 GB phones can't afford (Galaxy A10, 2026-11-21)
val checkShippedSnapshots by tasks.registering {
    val assetsDir = file("src/main/assets")
    doLast {
        val missing = mutableListOf<String>()
        assetsDir.resolve("dogboard/packs").listFiles().orEmpty().forEach { pack ->
            val manifest = pack.resolve("manifest.json").takeIf { it.isFile } ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val files = (groovy.json.JsonSlurper().parse(manifest) as Map<String, Any?>)["files"] as? Map<String, Map<String, Any?>> ?: return@forEach
            val key = listOf("lexicon", "bigrams").joinToString("") { (files[it]?.get("sha256") as? String)?.take(8).orEmpty() }
            if (key.length != 16) return@forEach
            for (name in listOf("lexicon", "bigrams")) {
                val f = assetsDir.resolve("dogboard/snapshot/${pack.name}-$name-$key.dgz")
                if (!f.isFile) missing += f.name
            }
        }
        if (missing.isNotEmpty()) throw GradleException(
            "Missing word-data snapshots (DOGBOARD_EXPORT_SNAPSHOT=1 ./gradlew :app:testDebugUnitTest --rerun): " + missing.joinToString()
        )
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkShippedSnapshots) }

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    // LiteRT (Apache-2.0): runs the neural language model (engine/lm)
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    // testImplementation(composeBom)
    // androidTestImplementation(composeBom)
    // My connection security code is: 9 8 5 0, check that you see the same numbers in my connection approval dialog.

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.window.core)
    implementation(libs.cache4k)
    implementation(libs.k3lp.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.okio)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)

    implementation(projects.lib.android)
    implementation(projects.lib.compose)
    implementation(projects.lib.kotlin)
    implementation(projects.lib.native)
    implementation(projects.lib.snygg)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // DogBoard UI tests on devices (emulators, Firebase Test Lab): taps injected with real timings
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

fun getGitCommitHash(short: Boolean = false): Provider<String> {
    if (!File(".git").exists()) {
        return providers.provider { "null" }
    }

    val execProvider = providers.exec {
        if (short) {
            commandLine("git", "rev-parse", "--short", "HEAD")
        } else {
            commandLine("git", "rev-parse", "HEAD")
        }
    }
    return execProvider.standardOutput.asText.map { it.trim() }
}

// DogBoard: the settings search index, generated from the settings pages' rows (user 2026-11-21: "can't you just
// automatically compile that list?"): every SetSwitchRow / SetSliderRow / SetNavRow / TitleSub title, with its
// description as search words, under the page its screen function belongs to. SettingsSearch adds hand-written synonyms.
val generateSettingsIndex by tasks.registering {
    val srcDirs = listOf(
        layout.projectDirectory.dir("src/main/kotlin/io/github/sillypuppyy/dogboard/settings"),
        layout.projectDirectory.dir("src/main/kotlin/io/github/sillypuppyy/dogboard/design/settings"),
    )
    val outFile = layout.buildDirectory.file("generated/settingsIndex/io/github/sillypuppyy/dogboard/settings/SettingsIndexGenerated.kt")
    srcDirs.forEach { inputs.dir(it) }
    outputs.file(outFile)
    doLast {
        // screen function (or file, for the design pages) -> page shown in results, route opened
        val pages = mapOf(
            "SoundVibrationScreen" to ("Sound and vibration" to "feedback"),
            "TypingCorrectionScreen" to ("Autocorrect and typing" to "typing"),
            "AutocorrectScreen" to ("Autocorrect and typing › Autocorrect" to "autocorrect"),
            "SuggestionsScreen" to ("Autocorrect and typing › Suggestions" to "suggestions"),
            "CapitalsScreen" to ("Autocorrect and typing › Capitals" to "capitals"),
            "PunctuationScreen" to ("Autocorrect and typing › Smart punctuation" to "punctuation"),
            "YourWordsScreen" to ("Autocorrect and typing › Your words" to "dictionary"),
            "DogClipboardScreen" to ("Clipboard" to "clipboard"),
            "DogEmojiScreen" to ("Emoji and GIFs" to "emoji"),
            "FineTuningScreen" to ("Timing and feel" to "finetuning"),
            "DogGesturesScreen" to ("Gestures" to "gestures"),
            "PrivacyScreen" to ("Privacy" to "privacy"),
            "DogAdvancedScreen" to ("Advanced" to "advanced"),
            "LanguagesScreen" to ("Languages" to "languages"),
            "SetLayout.kt" to ("Layout" to "layout"),
        )
        val row = Regex("""(?:Set(?:Switch|Slider|Nav)Row)\(\s*"[^"]*",\s*"([^"]+)"(?:,\s*"([^"]*)")?|TitleSub\(\s*"([^"]+)"(?:,\s*"([^"]*)")?""")
        val fn = Regex("""(?m)^(?:private |internal )?fun ([A-Za-z0-9_]+)\(""")
        fun esc(t: String) = t.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val entries = LinkedHashMap<String, String>()
        for (dir in srcDirs) for (f in dir.asFile.listFiles().orEmpty().filter { it.name.endsWith(".kt") }.sortedBy { it.name }) {
            val text = f.readText()
            val starts = fn.findAll(text).map { it.range.first to it.groupValues[1] }.toList()
            for (m in row.findAll(text)) {
                val owner = starts.lastOrNull { it.first < m.range.first }?.second
                val (page, route) = pages[owner] ?: pages[f.name] ?: continue
                val title = m.groupValues[1].ifEmpty { m.groupValues[3] }
                val desc = m.groupValues[2].ifEmpty { m.groupValues[4] }
                entries.putIfAbsent(title, "        SettingsSearch.Entry(\"${esc(title)}\", \"${esc(page)}\", \"$route\", \"${esc(desc)}\"),")
            }
        }
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText("// Generated by app/build.gradle.kts (generateSettingsIndex) from the settings pages. Do not edit.\n" +
            "package io.github.sillypuppyy.dogboard.settings\n\ninternal object SettingsIndexGenerated {\n    val entries = listOf(\n" +
            entries.values.joinToString("\n") + "\n    )\n}\n")
    }
}
tasks.named("preBuild") { dependsOn(generateSettingsIndex) }
