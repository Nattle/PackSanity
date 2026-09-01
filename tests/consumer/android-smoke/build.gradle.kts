plugins {
    id("com.android.application") 
}
android {
    namespace = "dev.nattle.packsanity.consumer"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.nattle.packsanity.consumer"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
}
dependencies {
    val version = providers.gradleProperty("packsanityVersion").get()
    implementation("dev.nattle.packsanity:loader:$version")
    implementation("dev.nattle.packsanity:runtime-android:$version")
}
