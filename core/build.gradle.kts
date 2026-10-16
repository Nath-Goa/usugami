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
    // Optional end-to-end fixture run: ./gradlew :core:test -PpdferFixtures=<dir>
    systemProperty("pdfer.fixtures", providers.gradleProperty("pdferFixtures").orElse("").get())
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
