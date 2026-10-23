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
    }
}

rootProject.name = "kotlin-fhir-cql-spike"
