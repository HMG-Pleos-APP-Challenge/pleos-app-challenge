import java.util.Properties

val cloudProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun cloudString(name: String): String = "\"" + (cloudProperties.getProperty(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    // Initial proposal from contract v0.1; peer approval is required before merging.
    namespace = "com.daehyundai.map"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.daehyundai.map"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SUPABASE_URL", cloudString("supabase.url"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", cloudString("supabase.publishableKey"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
