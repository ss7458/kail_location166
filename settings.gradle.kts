pluginManagement {
    repositories {
        // google() has a built-in content filter that can interfere with KSP
        // resolution (KSP is in Maven Central/Gradle Plugin Portal, not Google
        // Maven). Using the raw Maven URL avoids any ambiguity.
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "location"
include(":app")
include(":NewBlackbox:Bcore")
include(":NewBlackbox:black-reflection")
include(":NewBlackbox:compiler")
// Note: ":NewBlackbox:app" (BlackBox's standalone demo launcher) is intentionally
// not included — the main app only depends on :NewBlackbox:Bcore.
