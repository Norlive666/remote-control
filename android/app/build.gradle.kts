plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "io.github.norlive.remotecontrol"
    compileSdk = 35
    defaultConfig { applicationId = "io.github.norlive.remotecontrol"; minSdk = 26; targetSdk = 35; versionCode = 6; versionName = "1.3.2"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    val signingPath = System.getenv("RC_KEYSTORE")
    if (signingPath != null) signingConfigs.create("releaseKey") {
        storeFile = file(signingPath); storePassword = System.getenv("RC_STORE_PASSWORD")
        keyAlias = "remote-control"; keyPassword = System.getenv("RC_KEY_PASSWORD")
    }
    buildTypes { release { isMinifyEnabled = false; if (signingPath != null) signingConfig = signingConfigs.getByName("releaseKey") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("no.nordicsemi.android:ble:2.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
