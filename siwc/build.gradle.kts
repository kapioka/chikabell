import org.gradle.api.tasks.testing.Test
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.chikabell.siwc"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(libs.junit)
}
val protocolTests = tasks.register<JavaExec>("protocolTests") {
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("org.junit.runner.JUnitCore")
    args("com.chikabell.siwc.SiwcProtocolTest")
}
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(protocolTests)
        filter { includeTestsMatching("com.chikabell.siwc.__UnicodePathWorkaround__"); isFailOnNoMatchingTests = false }
    }
}
