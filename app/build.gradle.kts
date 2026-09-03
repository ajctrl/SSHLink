import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull
        ?.takeIf(String::isNotBlank)
        ?: keystoreProperties.getProperty(name)?.takeIf(String::isNotBlank)

val defaultVersionName = "0.3.8"
val defaultVersionCode = 11
val githubRefType = providers.environmentVariable("GITHUB_REF_TYPE").orNull
val githubRefName = providers.environmentVariable("GITHUB_REF_NAME").orNull
val releaseTag = githubRefName
    ?.let { Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(it) }
if (githubRefType == "tag" && githubRefName != null && releaseTag == null) {
    throw GradleException("Release tags must use the vMAJOR.MINOR.PATCH format: $githubRefName")
}
val releaseVersionName = releaseTag?.let {
    "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}"
} ?: defaultVersionName
val releaseVersionCode = releaseTag?.let {
    val major = it.groupValues[1].toInt()
    val minor = it.groupValues[2].toInt()
    val patch = it.groupValues[3].toInt()
    val generatedCode = major.toLong() * 1_000_000 + minor * 1_000 + patch
    require(generatedCode <= Int.MAX_VALUE) {
        "Release tag version is too large for the generated Android versionCode: $it"
    }
    generatedCode.toInt()
} ?: defaultVersionCode

val signingKeystoreFile = signingValue("SIGNING_KEYSTORE_FILE")
val signingKeyAlias = signingValue("SIGNING_KEY_ALIAS")
val signingStorePassword = signingValue("SIGNING_STORE_PASSWORD")
val signingKeyPassword = signingValue("SIGNING_KEY_PASSWORD")
val signingValues = listOf(
    signingKeystoreFile,
    signingKeyAlias,
    signingStorePassword,
    signingKeyPassword
)
val releaseSigningConfigured = signingValues.all { it != null }

if (signingValues.any { it != null } && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is only partially configured. Set all of " +
            "SIGNING_KEYSTORE_FILE, SIGNING_KEY_ALIAS, SIGNING_STORE_PASSWORD, " +
            "and SIGNING_KEY_PASSWORD."
    )
}

android {
    namespace = "com.example.sshlink"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.sshlink"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(signingKeystoreFile))
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
