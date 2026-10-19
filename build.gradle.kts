import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

// ./gradlew -PcqlVersion=<version> uses that cqframework version, from mavenLocal if published there.
val cqlVersion = (findProperty("cqlVersion") as String?) ?: "5.4.0"
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
            }
        }
        jvmTest.dependencies {
            // The reference result: cqframework's HAPI-based R4 model resolver, JVM only.
            implementation("org.cqframework:engine-fhir:$cqlVersion")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-base:8.10.0")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.10.0")
            // For kotlin-fhir-workflow's dynamicValue write-back code, copied into jvmTest (fhir-path version of its main).
            implementation("dev.ohs.fhir:fhir-path-r4:1.0.0-beta06")
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
