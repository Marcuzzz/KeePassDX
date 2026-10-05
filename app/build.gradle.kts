plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.kunzisoft.keepass"
    compileSdk = 36

    val gmsPackage = "com.google.android.gms"

    defaultConfig {
        // Fork: own applicationId so it installs side-by-side with the official KeePassDX
        applicationId = "com.marcuzzz.keepassdx"
        minSdk = 19
        targetSdk = 36
        versionCode = 45502
        versionName = "4.5.5"
        versionNameSuffix = "-mo"
        multiDexEnabled = true

        testApplicationId = "com.kunzisoft.keepass.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_PLAY_SERVICES_PACKAGE", "\"$gmsPackage\"")
        buildConfigField("String[]", "ICON_PACKS", "{\"classic\",\"material\"}")
        
        manifestPlaceholders["googleAndroidBackupAPIKey"] = "unused"
        manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Fork: test builds install next to the release app (different signing key)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // No debug-key fallback: without a keystore the release APK stays unsigned
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "version"
    productFlavors {
        create("libre") {
            dimension = "version"
            applicationIdSuffix = ".libre"
            buildConfigField("String", "BUILD_VERSION", "\"libre\"")
            buildConfigField("boolean", "CLOSED_STORE", "false")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeepassDXStyle_Red\"," +
                    "\"KeepassDXStyle_Red_Night\"," +
                    "\"KeepassDXStyle_Reply\"," +
                    "\"KeepassDXStyle_Reply_Night\"," +
                    "\"KeepassDXStyle_Purple\"," +
                    "\"KeepassDXStyle_Purple_Dark\"," +
                    "\"KeepassDXStyle_Dynamic_Light\"," +
                    "\"KeepassDXStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
        }
        create("free") {
            dimension = "version"
            applicationIdSuffix = ".free"
            buildConfigField("String", "BUILD_VERSION", "\"free\"")
            buildConfigField("boolean", "CLOSED_STORE", "true")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeepassDXStyle_Blue\"," +
                    "\"KeepassDXStyle_Blue_Night\"," +
                    "\"KeepassDXStyle_Red\"," +
                    "\"KeepassDXStyle_Red_Night\"," +
                    "\"KeepassDXStyle_Reply\"," +
                    "\"KeepassDXStyle_Reply_Night\"," +
                    "\"KeepassDXStyle_Purple\"," +
                    "\"KeepassDXStyle_Purple_Dark\"," +
                    "\"KeepassDXStyle_Dynamic_Light\"," +
                    "\"KeepassDXStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
            manifestPlaceholders["googleAndroidBackupAPIKey"] = "AEdPqrEAAAAIbRfbV8fHLItXo8OcHwrO0sSNblqhPwkc0DPTqg"
            manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
        }
    }

    sourceSets {
        getByName("libre") {
            res.srcDirs("src/libre/res")
        }
        getByName("free") {
            res.srcDirs("src/free/res")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.media)
    // Lifecycle - ViewModel - Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.google.material)
    // Token auto complete
    // From sources until https://github.com/splitwise/TokenAutoComplete/pull/422 fixed
    implementation(libs.tokenautocomplete)
    // Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Utilities
    implementation(libs.androidx.autofill)
    implementation(libs.joda.time)
    implementation(libs.chroma)
    implementation(libs.taptargetview)
    implementation(libs.commons.io)
    // Credentials
    implementation(libs.nbvcxz)
    implementation(libs.androidx.credentials)
    // Modules import
    implementation(project(":database"))
    implementation(project(":icon-pack"))
    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
