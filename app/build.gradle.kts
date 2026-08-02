import org.gradle.api.tasks.testing.Test

val releaseKeystorePath = providers.environmentVariable("CHIKABELL_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("CHIKABELL_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CHIKABELL_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CHIKABELL_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.chikabell.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chikabell.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

tasks.register("printReleaseSigningStatus") {
    group = "verification"
    description = "Prints whether release signing is configured without exposing secrets."
    doLast {
        println(if (hasReleaseSigning) "release-signing=configured" else "release-signing=not-configured")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.google.play.services.location)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

val allJvmUnitTests = tasks.register<JavaExec>("allJvmUnitTests") {
    group = "verification"
    description = "Runs all JVM tests without Gradle's Windows Unicode-path test worker issue."
    dependsOn("compileDebugUnitTestJavaWithJavac")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args(
        "com.chikabell.app.PlaceholderTest",
        "com.chikabell.app.LocationValidatorTest",
        "com.chikabell.app.RegistrationReadinessEvaluatorTest",
        "com.chikabell.app.GeofenceEventPolicyTest",
        "com.chikabell.app.DistanceCalculatorTest",
        "com.chikabell.app.RestorePolicyTest",
        "com.chikabell.app.SharedPlaceParserTest",
        "com.chikabell.app.SharedPlaceReviewPolicyTest",
        "com.chikabell.app.LocationTransferCodecTest",
        "com.chikabell.app.CooldownHoursTest",
        "com.chikabell.app.GoogleMapsShortLinkResolverTest",
        "com.chikabell.app.DestinationGuidanceFormatterTest",
        "com.chikabell.app.NearbyVerificationPolicyTest",
        "com.chikabell.app.ProcessGeofenceEventUseCaseTest",
        "com.chikabell.app.GeofenceEventBatchProcessorTest",
        "com.chikabell.app.NearbyNotificationContentFormatterTest",
        "com.chikabell.app.LocationDetailsExpansionStateTest",
        "com.chikabell.app.FindNearbySavedLocationsUseCaseTest",
        "com.chikabell.app.SharedRegistrationSessionTest",
        "com.chikabell.app.OpenLocationCodeDecoderTest",
        "com.chikabell.app.PngTextMetadataExtractorTest",
        "com.chikabell.app.SharedRegistrationReducerTest",
        "com.chikabell.app.SendTestNotificationUseCaseTest",
    )
}

// The generated Test worker mojibakes this workspace's Japanese path on Windows.
// Keep the standard command as the entry point while the stable runner executes the tests.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(allJvmUnitTests)
        filter {
            includeTestsMatching("com.chikabell.app.__UnicodePathWorkaround__")
            isFailOnNoMatchingTests = false
        }
    }
}

tasks.register<JavaExec>("phaseOneUnitTest") {
    group = "verification"
    description = "Runs the Phase 1 placeholder JVM test while Android unit test classpath is unstable."
    dependsOn("compileDebugUnitTestJavaWithJavac")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes")
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args("com.chikabell.app.PlaceholderTest")
}

tasks.register<JavaExec>("phaseTwoUnitTest") {
    group = "verification"
    description = "Runs Phase 2 JVM validation tests while Android unit test classpath is unstable."
    dependsOn("compileDebugUnitTestJavaWithJavac")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args("com.chikabell.app.LocationValidatorTest")
}

tasks.register<JavaExec>("phaseThreeUnitTest") {
    group = "verification"
    description = "Runs Phase 3 JVM permission and registration readiness tests."
    dependsOn("compileDebugUnitTestJavaWithJavac")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args("com.chikabell.app.RegistrationReadinessEvaluatorTest")
}

tasks.register<JavaExec>("phaseFourUnitTest") {
    group = "verification"
    description = "Runs Phase 4 JVM event policy tests."
    dependsOn("compileDebugUnitTestJavaWithJavac")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args(
        "com.chikabell.app.GeofenceEventPolicyTest",
        "com.chikabell.app.DistanceCalculatorTest",
    )
}
