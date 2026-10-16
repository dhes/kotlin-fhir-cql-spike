import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

val cqlVersion = "5.4.0"
val kotlinFhirVersion = "1.0.0-rc04"

kotlin {
    jvm()

    js {
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.cqframework:cql-to-elm:$cqlVersion")
            implementation("org.cqframework:engine:$cqlVersion")
            implementation("dev.ohs.fhir:fhir-model-r4:$kotlinFhirVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        jvmTest.dependencies {
            // The reference result: cqframework's HAPI-based R4 model resolver, JVM only.
            implementation("org.cqframework:engine-fhir:$cqlVersion")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-base:8.10.0")
            implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.10.0")
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
