import java.time.Duration

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.elith.precision"
    compileSdk = 35
    defaultConfig { applicationId = "com.elith.precision"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0"; testInstrumentationRunner = "android.test.InstrumentationTestRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.timeout.set(Duration.ofMinutes(4))
            test.testLogging { events("started", "passed", "failed", "skipped"); showStandardStreams = true }
            test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
    lint { abortOnError = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
