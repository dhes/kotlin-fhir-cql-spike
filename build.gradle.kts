import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

// ./gradlew -PcqlVersion=<version> uses that cqframework version, from mavenLocal if published there.
val cqlVersion = (findProperty("cqlVersion") as String?) ?: "5.4.0"
// ./gradlew -PengineVersion=<version> uses that kotlin-fhir-engine version, from mavenLocal if published there.
val engineVersion = (findProperty("engineVersion") as String?) ?: "2.0.0-alpha04"
val kotlinFhirVersion = "1.0.0-rc04"
// ./gradlew -PcrVersion=<version> uses that clinical-reasoning version (steps 11 and 17), from mavenLocal if published
// there.
val crVersion = (findProperty("crVersion") as String?) ?: "4.13.0"

kotlin {
    jvm()

    js {
        // kotlin-fhir-engine's JS code uses `import.meta`, so Node loads it as an ES module (step 13).
        useEsModules()
        nodejs {
            testTask {
                useMocha { timeout = "3600s" }
                // kotlinx-io needs a global `require` in an ES module (Kotlin/kotlinx-io#345).
                nodeJsArgs += listOf("--import", file("src/jsTest/node/global-require.mjs").toURI().toString())
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs {
            testTask { useMocha { timeout = "3600s" } }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.cqframework:cql-to-elm:$cqlVersion")
            implementation("org.cqframework:engine:$cqlVersion")
            implementation("dev.ohs.fhir:fhir-model-r4:$kotlinFhirVersion")
        }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/testPaths"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                // Step 9 (JVM) and step 13 (JS and Wasm): kotlin-fhir-engine behind kotlin-fhir-workflow's WorkflowRepository.
                implementation("dev.ohs.fhir:fhir-engine:$engineVersion")
                // Step 16: kotlin-fhir-knowledge main at 45b6960, built locally against kotlin-fhir rc04 and
                // kotlin-fhir-engine's Room and SQLite versions (patches/kotlin-fhir-knowledge-45b6960-rc04.patch).
                implementation("dev.ohs.fhir:fhir-knowledge:2.0.0-spike-45b6960-rc04")
            }
        }
        // Step 15: kotlin-fhir-workflow main at 214c56d, built locally against kotlin-fhir rc04 (see settings.gradle.kts),
        // for its two targets in common with the spike, JVM and Wasm. Its common metadata did not build against a local
        // kotlin-fhirpath, so each target uses its own artifact and compiles the step's sources itself.
        jvmMain {
            kotlin.srcDir("src/workflowMain/kotlin")
            dependencies { implementation("dev.ohs.fhir:fhir-workflow-desktop:2.0.0-spike-214c56d-rc04") }
        }
        wasmJsMain {
            kotlin.srcDir("src/workflowMain/kotlin")
            dependencies { implementation("dev.ohs.fhir:fhir-workflow-wasm-js:2.0.0-spike-214c56d-rc04") }
        }
        jvmTest { kotlin.srcDir("src/workflowTest/kotlin") }
        wasmJsTest { kotlin.srcDir("src/workflowTest/kotlin") }
        webMain.dependencies {
            // Step 14: the UCUM service on JS and Wasm, where cqframework has none.
            implementation(npm("@lhncbc/ucum-lhc", "7.1.10"))
        }
        webTest.dependencies {
            // Step 16: the web targets have no file system okio implements, so WHO's unpacked package is put in one.
            implementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
            // kotlin-fhir-engine's SQLite worker, which its published JS and Wasm artifacts can't bring (see its README,
            // "Required workaround for consumers targeting js/wasmJs"), with what it needs to run under Node.
            implementation(npm("sqlite-wasm-worker", layout.projectDirectory.dir("src/webTest/npm/sqlite-wasm-worker").asFile))
        }
        jvmTest.dependencies {
            // The reference result: cqframework's HAPI-based R4 model resolver, JVM only. HAPI 8.12.0 since step 11
            // (clinical-reasoning 4.13.0 needs it); steps 1–10 ran on 8.10.0.
            implementation("org.cqframework:engine-fhir:$cqlVersion")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-base:8.12.0")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.12.0")
            // For kotlin-fhir-workflow's dynamicValue write-back code, copied into jvmTest (fhir-path version of its main).
            implementation("dev.ohs.fhir:fhir-path-r4:1.0.0-beta06")
            // Step 11: clinical-reasoning's RepositoryTerminologyProvider as the reference (built on cqframework 5.4.0).
            implementation("org.opencds.cqf.fhir:cqf-fhir-cql:$crVersion")
            // Step 17: clinical-reasoning's $apply (PlanDefinitionProcessor), same version.
            implementation("org.opencds.cqf.fhir:cqf-fhir-cr:$crVersion")
            // HAPI's FHIRPath, which clinical-reasoning uses, needs its validation support.
            implementation("ca.uhn.hapi.fhir:hapi-fhir-validation:8.12.0")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.12.0")
        }
    }
}

// Tests on every platform read fixtures from the project directory by absolute path, and know the cqframework version.
val generateTestPaths by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/testPaths")
    val projectPath = projectDir.absolutePath
    val version = cqlVersion
    inputs.property("cqlVersion", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("ProjectDir.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package io.github.dhes.kotlinfhircql\n\nconst val PROJECT_DIR = \"$projectPath\"\n\n" +
                "/** The cqframework version the tests run against (-PcqlVersion). */\nconst val CQL_VERSION = \"$version\"\n"
        )
    }
}

// Step 16: WHO's package, unpacked for the JS and Wasm tests, where kotlin-fhir-knowledge can't unpack it (no gzip).
val unpackWhoPackage by tasks.registering(Copy::class) {
    from(tarTree(resources.gzip(file("third_party/smart-immunizations-0.2.0/smart.who.int.immunizations-0.2.0.tgz"))))
    into(layout.buildDirectory.dir("who-package"))
}

tasks.matching { it.name.startsWith("compileTestKotlin") || it.name == "compileTestDevelopmentExecutableKotlinJs" }
    .configureEach { dependsOn(generateTestPaths, unpackWhoPackage) }

tasks.withType<Test>().configureEach {
    // Step 17 keeps clinical-reasoning's compiled libraries and WHO's package for the rest of the run; Gradle's
    // default of 512 MB then runs out in the tests after it.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
