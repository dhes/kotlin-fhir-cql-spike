pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Room and SQLite, which kotlin-fhir-engine uses (step 9).
        google { content { includeGroupByRegex("androidx\\..*") } }
        // Local cqframework builds, used only when -PcqlVersion is given.
        if (providers.gradleProperty("cqlVersion").isPresent) {
            mavenLocal { content { includeGroup("org.cqframework") } }
        }
        // Local kotlin-fhir-engine builds, used only when -PengineVersion is given.
        if (providers.gradleProperty("engineVersion").isPresent) {
            mavenLocal { content { includeModuleByRegex("dev\\.ohs\\.fhir", "fhir-engine.*") } }
        }
        // Local clinical-reasoning builds, used only when -PcrVersion is given.
        if (providers.gradleProperty("crVersion").isPresent) {
            mavenLocal { content { includeGroup("org.opencds.cqf.fhir") } }
        }
        // Steps 15 and 16: kotlin-fhir-workflow main at 214c56d, kotlin-fhirpath main at 461a7b9 and kotlin-fhir-knowledge
        // main at 45b6960, built locally (*-spike-*).
        mavenLocal { content { includeVersionByRegex("dev\\.ohs\\.fhir", "fhir-(workflow|path|knowledge).*", ".*-spike-.*") } }
    }
}

rootProject.name = "kotlin-fhir-cql-spike"

// Step 3 of the browser work: a page that runs WHO's test cases through $apply (demo/README.md).
include("demo")
