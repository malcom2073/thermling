@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("com.android.application") version "8.7.3"
}

group = "com.thermling"
version = "0.4.0"

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("com.fazecast:jSerialComm:2.11.0")
                implementation("net.java.dev.jna:jna:5.19.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.uiTest)
                implementation(compose.desktop.currentOs)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.10.1")
                implementation("androidx.core:core-ktx:1.15.0")
            }
        }
    }
}

val releaseSigningProperties = Properties().apply {
    val path = System.getenv("THERMLING_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/thermling/keystore.properties"
    val propertiesFile = file(path)
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.thermling.android"
    compileSdk = 35
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.thermling"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = project.version.toString()
    }

    signingConfigs {
        if (releaseSigningProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // AGP's new VariantOutput API does not expose outputFileName yet, so use
    // the legacy variant API to include the version in the APK filename.
    applicationVariants.configureEach {
        val variant = this
        val variantVersion = variant.versionName ?: project.version.toString()
        val unsignedSuffix =
            if (variant.name == "release" && releaseSigningProperties.getProperty("storeFile") == null) "-unsigned" else ""
        for (output in variant.outputs) {
            val outputImpl = output as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "thermling-$variantVersion-${variant.name}$unsignedSuffix.apk"
        }
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

compose.desktop {
    application {
        mainClass = "com.thermling.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "Thermling"
            packageVersion = project.version.toString()
            description = "Label composer for Nelko P21/P22 thermal printers"
            vendor = "Thermling"
            linux {
                packageName = "thermling"
            }
        }
    }
}

val packageLinuxAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a portable Linux .AppImage from the jpackage app image"

    dependsOn("createDistributable")

    val appName = "Thermling"
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/$appName")
    val outputDir = layout.buildDirectory.dir("compose/binaries/main/appimage")
    val appVersion = project.version.toString()
    val appArch = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> System.getProperty("os.arch")
    }
    val script = layout.projectDirectory.file("scripts/package-appimage.sh")

    inputs.dir(appImageDir)
    inputs.file(script)
    outputs.file(outputDir.map { it.file("$appName-$appVersion-$appArch.AppImage") })

    commandLine(
        "bash",
        script.asFile.absolutePath,
        appImageDir.get().asFile.absolutePath,
        outputDir.get().asFile.absolutePath,
        appVersion,
        appArch,
    )

    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    System.getProperty("thermling.btMac")?.let { systemProperty("THERMLING_BT_MAC", it) }
}
