pluginManagement {
    repositories {
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/releases") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            content {
                // KSP Gradle plugin (com.google.devtools.ksp) is published to the
                // Gradle Plugin Portal / Maven Central, NOT Google Maven. The
                // google() repo above claims the com.google.* group via a content
                // filter, which makes Gradle restrict com.google.* resolution to
                // repos that also declare that group. Without this filter the
                // Aliyun gradle-plugin mirror is skipped and CI (no access to
                // gradlePluginPortal) cannot resolve KSP.
                includeGroupByRegex("com\\.google.*")
            }
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google {
            content {
                // NOTE: intentionally NOT claiming com.google.* here. Gradle
                // makes group resolution exclusive to repos that declare a
                // matching content filter, so claiming com.google.* would
                // prevent the Aliyun gradle-plugin mirror (which carries KSP,
                // google-services, firebase plugins) from being consulted on
                // CI where gradlePluginPortal is unreachable.
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/releases") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "location"
include(":app")
include(":NewBlackbox:Bcore")
include(":NewBlackbox:black-reflection")
include(":NewBlackbox:compiler")
// Note: ":NewBlackbox:app" (BlackBox's standalone demo launcher) is intentionally
// not included — the main app only depends on :NewBlackbox:Bcore.
 
