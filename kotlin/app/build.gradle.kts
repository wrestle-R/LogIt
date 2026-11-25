plugins { id("com.android.application") }
if (file("google-services.json").exists()) { apply(plugin = "com.google.gms.google-services") }
android {
 namespace = "dev.logit.app"
 compileSdk = 36
 defaultConfig { applicationId = "com.romeiro.logit"; minSdk = 26; targetSdk = 36; versionCode = 11; versionName = "0.3.6"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
 implementation("com.google.firebase:firebase-auth")
 implementation("com.google.firebase:firebase-firestore")
 implementation("com.google.android.gms:play-services-auth:21.4.0")
 implementation("androidx.work:work-runtime:2.10.1")
 implementation("androidx.core:core-splashscreen:1.2.0")
 testImplementation("junit:junit:4.13.2")
}
