import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin JVM module: the package parser, the selection rules, the pacing presets, the model, the response
// classifier and the executor. No Android, no network API (CLAUDE.md rules 1-4 and 18): the transport is an
// interface that :net implements with OkHttp.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures` // the executor's fakes, shared with :net's mock-server suite
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
