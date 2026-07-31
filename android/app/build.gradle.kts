import com.android.build.api.variant.BuildConfigField
import java.time.LocalDate
import java.time.ZoneId

plugins {
    alias(libs.plugins.android.application)
}

val apkBuildDate = providers.provider {
    LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
}

android {
    namespace = "com.example.srmremoter"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.srmremoter"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    androidResources.localeFilters += listOf("zh")

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "BUILD_DATE",
            apkBuildDate.map { date ->
                BuildConfigField("String", "\"$date\"", "APK packaging date (Asia/Shanghai)")
            },
        )
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
