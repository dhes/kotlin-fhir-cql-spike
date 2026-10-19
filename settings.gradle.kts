pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Local cqframework builds, used only when -PcqlVersion is given.
        if (providers.gradleProperty("cqlVersion").isPresent) {
            mavenLocal { content { includeGroup("org.cqframework") } }
        }
    }
}

rootProject.name = "kotlin-fhir-cql-spike"
