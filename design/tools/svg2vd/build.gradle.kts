plugins {
    java
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Google's own SVG to VectorDrawable converter (the one behind Android Studio's "Vector Asset" import).
    implementation("com.android.tools:sdk-common:32.5.1")
}

application {
    mainClass.set("Svg2Vd")
}

// Defaults convert every icon of the project. Override with -PsvgDir=... -PoutDir=... if needed.
val projectRoot = layout.projectDirectory.dir("../../..")
val svgDir = (findProperty("svgDir") as String?) ?: projectRoot.dir("design/icons/svg").asFile.path
val outDir = (findProperty("outDir") as String?) ?: projectRoot.dir("app/src/main/res/drawable").asFile.path

tasks.named<JavaExec>("run") {
    args(svgDir, outDir)
}
