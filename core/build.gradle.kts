plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Plain Kotlin: the PDF engine has no Android dependencies, so it is tested on the desktop JVM.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Optional end-to-end fixture run: ./gradlew :core:test -PusugamiFixtures=<dir>
    systemProperty("usugami.fixtures", providers.gradleProperty("usugamiFixtures").orElse("").get())
    // TranslationsTest reads the app's strings and the store texts: a change to one of them alone
    // runs the tests again.
    inputs.files(fileTree("../app/src") { include("*/res/values*/*.xml") })
        .withPropertyName("appStrings").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree("../fastlane/metadata") { include("**/*.txt") },
        fileTree("../src/lite/fastlane/metadata") { include("**/*.txt") },
    ).withPropertyName("storeTexts").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
