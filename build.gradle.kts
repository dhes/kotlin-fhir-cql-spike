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

kotlin {
    jvm()

    js {
        nodejs {
            testTask { useMocha { timeout = "3600s" } }
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
            }
        }
        jvmTest.dependencies {
            // The reference result: cqframework's HAPI-based R4 model resolver, JVM only. HAPI 8.12.0 since step 11
            // (clinical-reasoning 4.13.0 needs it); steps 1–10 ran on 8.10.0.
            implementation("org.cqframework:engine-fhir:$cqlVersion")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-base:8.12.0")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.12.0")
            // For kotlin-fhir-workflow's dynamicValue write-back code, copied into jvmTest (fhir-path version of its main).
            implementation("dev.ohs.fhir:fhir-path-r4:1.0.0-beta06")
            // Step 9: kotlin-fhir-engine behind kotlin-fhir-workflow's WorkflowRepository.
            implementation("dev.ohs.fhir:fhir-engine:$engineVersion")
            // Step 11: clinical-reasoning's RepositoryTerminologyProvider as the reference (built on cqframework 5.4.0).
            implementation("org.opencds.cqf.fhir:cqf-fhir-cql:4.13.0")
            // HAPI's FHIRPath, which clinical-reasoning uses, needs its validation support.
            implementation("ca.uhn.hapi.fhir:hapi-fhir-validation:8.12.0")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:8.12.0")
        }
    }
}

// Tests on every platform read fixtures from the project directory by absolute path.
val generateTestPaths by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/testPaths")
    val projectPath = projectDir.absolutePath
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("ProjectDir.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package io.github.dhes.kotlinfhircql\n\nconst val PROJECT_DIR = \"$projectPath\"\n")
    }
}

tasks.matching { it.name.startsWith("compileTestKotlin") || it.name == "compileTestDevelopmentExecutableKotlinJs" }
    .configureEach { dependsOn(generateTestPaths) }

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
