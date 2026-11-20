import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: turns bank and UPI SMS text into structured payments.
// No Android dependencies, so it is tested on the JVM.
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
