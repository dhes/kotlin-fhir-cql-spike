import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// A page that runs WHO's immunization test cases through kotlin-fhir-workflow's $apply in the browser (README.md).
// It compiles the spike's own code for Wasm: its main sources, and the few test files that read and run WHO's suite.

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val cqlVersion = (findProperty("cqlVersion") as String?) ?: "5.5.0"
val kotlinFhirVersion = "1.0.0-rc04"
// kotlin-fhir-workflow `main` at 214c56d with the fix offered on #9 (dhes/kotlin-fhir-workflow 6ba0f6c), built locally
// against kotlin-fhir rc04 like the spike's 214c56d build (README, step 15). -PworkflowVersion=2.0.0-spike-214c56d-rc04
// runs `main` itself, with the spike rewriting the dynamicValue paths instead (WhoApply.conform).
val workflowVersion = (findProperty("workflowVersion") as String?) ?: "2.0.0-spike-6ba0f6c-rc04"
val workflowFix = "6ba0f6c" in workflowVersion
val spike = rootProject.projectDir

// The spike's test files the page reuses, copied so that the rest of its tests stay out of the build.
val suiteSources by tasks.registering(Sync::class) {
    from(spike.resolve("src/commonTest/kotlin")) {
        include("**/WhoSuite.kt", "**/MeaslesFixtures.kt", "**/PlatformUcum.kt", "**/InMemoryWorkflowRepository.kt")
    }
    into(layout.buildDirectory.dir("suite/commonMain"))
}
val suiteWasmSources by tasks.registering(Sync::class) {
    from(spike.resolve("src/workflowTest/kotlin")) { include("**/WhoApply.kt") }
    from(spike.resolve("src/wasmJsTest/kotlin")) { include("**/PlatformUcum.wasmJs.kt", "**/WhoTestDateTime.wasmJs.kt") }
    into(layout.buildDirectory.dir("suite/wasmJsMain"))
}

// The versions the page names.
val versions by tasks.registering {
    val out = layout.buildDirectory.dir("generated/versions")
    val values = mapOf("CQL_VERSION" to cqlVersion, "KOTLIN_FHIR_VERSION" to kotlinFhirVersion, "WORKFLOW_VERSION" to workflowVersion)
    val fix = workflowFix
    inputs.property("fix", fix)
    inputs.property("values", values)
    outputs.dir(out)
    doLast {
        val file = out.get().file("Versions.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package io.github.dhes.kotlinfhircql\n\n" +
                "/** Unused on the page, which reads the files it fetched (MeaslesFixtures.preloaded). */\n" +
                "const val PROJECT_DIR = \"\"\n\n" +
                values.entries.joinToString("") { (k, v) -> "const val $k = \"$v\"\n" } +
                "\n/** Whether kotlin-fhir-workflow has the fix offered on #9, so the page leaves WHO's paths as published. */\n" +
                "const val WORKFLOW_FIX = $fix\n"
        )
    }
}

// WHO's files the page fetches, as one JSON object from project-relative path to text.
val fixtures by tasks.registering {
    val dirs = listOf(
        "fixtures/who-suite",
        "third_party/smart-immunizations-0.2.0/cql",
        "third_party/smart-immunizations-0.2.0/valuesets",
        "third_party/smart-immunizations-0.2.0/definitions",
    )
    val files = listOf(
        "third_party/cqframework-quick-5.4.0/fhir-modelinfo-4.0.1.xml",
        "third_party/cqframework-quick-5.4.0/FHIRHelpers-4.0.1.cql",
    )
    dirs.forEach { inputs.dir(spike.resolve(it)) }
    files.forEach { inputs.file(spike.resolve(it)) }
    val out = layout.buildDirectory.dir("generated/fixtures")
    outputs.dir(out)
    doLast {
        val all = dirs.flatMap { d -> spike.resolve(d).walkTopDown().filter { it.isFile }.toList() } + files.map(spike::resolve)
        val map = all.associate { it.relativeTo(spike).invariantSeparatorsPath to it.readText() }.toSortedMap()
        out.get().file("fixtures.json").asFile.apply { parentFile.mkdirs() }.writeText(groovy.json.JsonOutput.toJson(map))
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "demo.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(spike.resolve("src/commonMain/kotlin"))
            kotlin.srcDir(suiteSources)
            kotlin.srcDir(versions)
            dependencies {
                implementation("org.cqframework:cql-to-elm:$cqlVersion")
                implementation("org.cqframework:engine:$cqlVersion")
                implementation("dev.ohs.fhir:fhir-model-r4:$kotlinFhirVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        wasmJsMain {
            kotlin.srcDir(spike.resolve("src/workflowMain/kotlin"))
            kotlin.srcDir(spike.resolve("src/webMain/kotlin"))
            kotlin.srcDir(suiteWasmSources)
            resources.srcDir(fixtures)
            dependencies {
                implementation("dev.ohs.fhir:fhir-workflow-wasm-js:$workflowVersion")
                implementation(npm("@lhncbc/ucum-lhc", "7.1.10"))
            }
        }
    }
}
