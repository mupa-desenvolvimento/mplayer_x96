import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.mupa.player.enterprise"
    compileSdk = 34

    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { localProps.load(it) }
    }

    fun getConfig(name: String): String? {
        return providers.gradleProperty(name).orNull
            ?: localProps.getProperty(name)
            ?: System.getenv(name)
    }

    defaultConfig {
        applicationId = "com.mupa.player.x96"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Dedicated to Android-9-and-below TV boxes with no camera/barcode scanner
        // (see DeviceIdentityManager.resolveMacAddress()) — every device running this
        // app uses the WiFi MAC as its serial, must match the ARGOS Agent legacy
        // flavor's algorithm byte-for-byte so both apps register the same device.
        buildConfigField("Boolean", "USE_MAC_AS_SERIAL", "true")

        fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

        val supabaseToken = (getConfig("SUPABASE_TOKEN") ?: "").trim()

        buildConfigField(
            "String",
            "SUPABASE_TOKEN",
            "\"${supabaseToken.escapeForBuildConfig()}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_DEVICE_RPC_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/rpc/get_dispositivo_por_serial\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_CREATE_DEVICE_RPC_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/rpc/create_dispositivo\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_COMPANIES_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/companies\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_DEVICE_COMMANDS_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/device_commands\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_DEVICE_EXECUTION_LOGS_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/device_execution_logs\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_REALTIME_URL",
            "\"wss://iurqddkuihjsmxubibao.supabase.co/realtime/v1/websocket\"",
        )
    }

    signingConfigs {
        create("release") {
            fun requireProp(name: String): String {
                return (getConfig(name) ?: "").trim().ifBlank {
                    throw GradleException(
                        "Release signing not configured. Missing $name. " +
                            "Set it in local.properties or as Gradle property/environment variable.",
                    )
                }
            }

            val storeFilePath = (getConfig("RELEASE_STORE_FILE") ?: "").trim()
            if (storeFilePath.isBlank()) return@create

            storeFile = file(storeFilePath)
            if (!storeFile!!.exists()) {
                throw GradleException("Release keystore not found at: $storeFilePath")
            }
            storePassword = requireProp("RELEASE_STORE_PASSWORD")
            keyAlias = requireProp("RELEASE_KEY_ALIAS")
            keyPassword = requireProp("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val storeFilePath = (getConfig("RELEASE_STORE_FILE") ?: "").trim()
            if (storeFilePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "api"
    productFlavors {
        create("legacy") {
            dimension = "api"
            minSdk = 21
        }
        create("modern") {
            dimension = "api"
            minSdk = 24
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    implementation("io.coil-kt:coil:2.6.0")

    implementation("androidx.palette:palette-ktx:1.0.0")

    add("modernImplementation", files("libs/EasyLayerUnificadaVarejo_1_0_3.aar"))

    // Unit testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.json:json:20231013")
}

tasks.register("assembleBothDebug") {
    dependsOn("assembleLegacyDebug", "assembleModernDebug")
}
