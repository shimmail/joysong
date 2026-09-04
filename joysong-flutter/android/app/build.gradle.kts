import java.io.FileInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

val uatSigningFile = rootProject.file("key.uat.properties")
val prodSigningFile = rootProject.file("key.prod.properties")
val requiredSigningProperties = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
)

fun decodeDartDefines(): Map<String, String> {
    val encodedDefines = providers.gradleProperty("dart-defines").orNull
        ?.split(',')
        .orEmpty()
        .filter(String::isNotBlank)
    return encodedDefines.associate { encoded ->
        val decoded = try {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw GradleException("Invalid base64 value in dart-defines.", error)
        }
        val separator = decoded.indexOf('=')
        if (separator <= 0) {
            throw GradleException("Invalid dart-define entry: $decoded")
        }
        decoded.substring(0, separator) to decoded.substring(separator + 1)
    }
}

fun validateVariantEnvironment(expectedEnvironment: String) {
    val defines = decodeDartDefines()
    val actualEnvironment = defines["APP_ENV"]
    if (actualEnvironment != expectedEnvironment) {
        throw GradleException(
            "Android flavor requires --dart-define=APP_ENV=$expectedEnvironment; " +
                "received ${actualEnvironment ?: "no value"}.",
        )
    }
    val apiBaseUrl = defines["API_BASE_URL"]
        ?: throw GradleException(
            "Android flavor requires --dart-define=API_BASE_URL=https://...",
        )
    val uri = try {
        URI(apiBaseUrl)
    } catch (error: Exception) {
        throw GradleException("API_BASE_URL is not a valid URL: $apiBaseUrl", error)
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        throw GradleException("Android flavor API_BASE_URL must be an HTTPS URL with a host.")
    }
}

val dartDefines = decodeDartDefines()

fun loadSigningProperties(propertiesFile: File): Properties? {
    if (!propertiesFile.isFile) return null
    val properties = Properties()
    FileInputStream(propertiesFile).use(properties::load)
    val complete = requiredSigningProperties.all {
        !properties.getProperty(it).isNullOrBlank()
    }
    if (!complete) return null
    val keyStore = rootProject.file(properties.getProperty("storeFile"))
    return properties.takeIf { keyStore.isFile }
}

fun validateSigningProperties(flavor: String, propertiesFile: File) {
    if (!propertiesFile.isFile) {
        throw GradleException(
            "Missing $flavor release signing file: ${propertiesFile.path}. " +
                "Copy the matching .example file and inject the secret values.",
        )
    }
    val properties = Properties()
    FileInputStream(propertiesFile).use(properties::load)
    val missing = requiredSigningProperties.filter {
        properties.getProperty(it).isNullOrBlank()
    }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Incomplete $flavor release signing file; missing: ${missing.joinToString()}.",
        )
    }
    val keyStore = rootProject.file(properties.getProperty("storeFile"))
    if (!keyStore.isFile) {
        throw GradleException("Missing $flavor release keystore: ${keyStore.path}.")
    }
}

val uatSigningProperties = loadSigningProperties(uatSigningFile)
val prodSigningProperties = loadSigningProperties(prodSigningFile)

android {
    namespace = "com.joysong.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.joysong.app"
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["appEnvironment"] = dartDefines["APP_ENV"] ?: "undefined"
        manifestPlaceholders["apiBaseUrl"] = dartDefines["API_BASE_URL"] ?: "undefined"
    }

    signingConfigs {
        uatSigningProperties?.let { properties ->
            create("uatRelease") {
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
            }
        }
        prodSigningProperties?.let { properties ->
            create("prodRelease") {
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationId = "com.joysong.app"
            manifestPlaceholders["deepLinkScheme"] = "joysong"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        create("uat") {
            dimension = "environment"
            applicationId = "com.joysong.app.uat"
            manifestPlaceholders["deepLinkScheme"] = "joysong-uat"
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            signingConfigs.findByName("uatRelease")?.let { signingConfig = it }
        }
        create("prod") {
            dimension = "environment"
            applicationId = "com.joysong.app"
            manifestPlaceholders["deepLinkScheme"] = "joysong"
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            signingConfigs.findByName("prodRelease")?.let { signingConfig = it }
        }
    }

    buildTypes {
        debug {
            // Keep the registered OAuth package name in debug builds. Google
            // Sign-In validates the exact applicationId together with SHA-1.
            versionNameSuffix = "-flutterdev"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Signing is selected by the UAT/Prod flavor. Validation tasks
            // below prevent an unsigned release from being produced.
        }
    }
}

val validateUatSigning = tasks.register("validateUatSigning") {
    doLast { validateSigningProperties("UAT", uatSigningFile) }
}
val validateProdSigning = tasks.register("validateProdSigning") {
    doLast { validateSigningProperties("Prod", prodSigningFile) }
}
val validateUatEnvironment = tasks.register("validateUatEnvironment") {
    doLast { validateVariantEnvironment("uat") }
}
val validateProdEnvironment = tasks.register("validateProdEnvironment") {
    doLast { validateVariantEnvironment("production") }
}
val rejectDevelopmentPublishing = tasks.register("rejectDevelopmentPublishing") {
    doLast {
        throw GradleException(
            "Development is debug-only. Build the uat or prod flavor for a release artifact.",
        )
    }
}

tasks.configureEach {
    val isUatVariant = Regex("Uat(Debug|Profile|Release)", RegexOption.IGNORE_CASE)
        .containsMatchIn(name)
    val isProdVariant = Regex("Prod(Debug|Profile|Release)", RegexOption.IGNORE_CASE)
        .containsMatchIn(name)
    if (isUatVariant) {
        dependsOn(validateUatEnvironment)
    }
    if (isProdVariant) {
        dependsOn(validateProdEnvironment)
    }
    if (name.contains("UatRelease", ignoreCase = true)) {
        dependsOn(validateUatSigning)
    }
    if (name.contains("ProdRelease", ignoreCase = true)) {
        dependsOn(validateProdSigning)
    }
    if (name.contains("DevelopmentRelease", ignoreCase = true)) {
        dependsOn(rejectDevelopmentPublishing)
    }
}

flutter {
    source = "../.."
}
