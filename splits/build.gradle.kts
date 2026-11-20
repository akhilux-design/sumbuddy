import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: the split maths shared with the server (docs/server.md section 6). The server implements the
// same rules in TypeScript; both run the vectors in shared/vectors. No Android dependencies, so it is tested on the
// JVM.
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

tasks.test {
    // The vector files live outside the module: shared/vectors (the contract's place), or server/test/vectors while
    // the server keeps its copy there. The first folder that has them is used.
    val folders = listOf("shared/vectors", "server/test/vectors").map { rootProject.layout.projectDirectory.dir(it) }
    systemProperty("vectors.dirs", folders.joinToString(File.pathSeparator) { it.asFile.absolutePath })
    folders.forEachIndexed { index, folder ->
        inputs.files(fileTree(folder) { include("*.json") }).withPropertyName("vectors$index").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
