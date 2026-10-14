plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "com.chikabell.siwcpoc"
    compileSdk = 37
    defaultConfig { applicationId = "com.chikabell.siwcpoc"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":siwc"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
