pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Heeler"

// Pure-JVM modules first: the herdr wire protocol and Transport seam, then the
// SSH transport that implements it. Both run their tests on the JVM without an
// emulator. `:app` is the only Android module.
include(":herdr")
include(":ssh")
include(":app")
