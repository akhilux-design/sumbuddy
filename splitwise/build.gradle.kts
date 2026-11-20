import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: reads a Splitwise "Export as spreadsheet" file into an import plan (docs/splitwise-import.md
// section 4, docs/server.md 4.17). No Android dependencies, so it is tested on the JVM. The file never leaves the
// phone and nothing here logs.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // SplitMath.MAX_AMOUNT_MINOR and MAX_PARTICIPANTS; Balances in the tests.
    implementation(project(":splits"))

    testImplementation(libs.junit)
}
