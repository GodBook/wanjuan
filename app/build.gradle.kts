import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
val signing = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
android {
    namespace = "io.github.godbook.wanjuan"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.godbook.wanjuan"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 103
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"GodBook/wanjuan\"")
    }
    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.jayway.jsonpath:json-path:2.9.0")
    implementation("com.google.re2j:re2j:1.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas"); arg("room.generateKotlin", "false") } }
tasks.withType<Test>().configureEach {
    // Windows Java argument files can decode non-ASCII classpath entries with the native code page.
    // Stage only such entries in a task-private ASCII runtime directory; test discovery remains unchanged.
    if (System.getProperty("os.name").startsWith("Windows")) doFirst {
        val runtime = File(System.getProperty("java.io.tmpdir"), "wanjuan-jvm-tests-${rootDir.absolutePath.hashCode()}-$name")
        val mapped = classpath.files.map { entry ->
            if (entry.absolutePath.any { it.code > 127 }) {
                val target = File(runtime, "${entry.absolutePath.hashCode()}-${entry.name}")
                if (entry.exists()) {
                    if (entry.isDirectory) entry.copyRecursively(target, overwrite = true)
                    else { target.parentFile.mkdirs(); entry.copyTo(target, overwrite = true) }
                }
                target
            } else entry
        }
        classpath = files(mapped)
    }
}
