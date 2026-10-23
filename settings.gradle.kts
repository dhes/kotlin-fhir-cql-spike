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
    }
}

rootProject.name = "kotlin-fhir-cql-spike"
