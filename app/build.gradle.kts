import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

// Release signing: CI passes the keystore through PDFER_* environment variables; a local build reads
// the git-ignored keystore.properties. Without either, release APKs are left unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingValue(env: String, key: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)

android {
    namespace = "io.github.nath_goa.pdfer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nath_goa.pdfer"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        val storePath = signingValue("PDFER_KEYSTORE_PATH", "storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("PDFER_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("PDFER_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("PDFER_KEY_PASSWORD", "keyPassword")
                enableV1Signing = false // only needed below Android 7.0; minSdk is 8.0
                enableV2Signing = true
                enableV3Signing = true // allows rotating the key later
            }
        }
    }

    // Two editions with the same app ID, so either one can be installed over the other.
    // standard: text recognition comes from Google Play services (small APK).
    // offline: the text recognition model is built into the APK (no Play services needed).
    flavorDimensions += "ocr"
    productFlavors {
        create("standard") {
            dimension = "ocr"
            isDefault = true
            buildConfigField("boolean", "OCR_BUNDLED", "false")
        }
        create("offline") {
            dimension = "ocr"
            buildConfigField("boolean", "OCR_BUNDLED", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // ML Kit pulls in AppCompat, whose own keep rule for SearchView would keep ~200 KB of
            // unused AppCompat code and resources. The app is pure Compose and never uses it.
            optimization { keepRules { ignoreFrom("androidx.appcompat:appcompat") } }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The app is English only; this drops 80+ unused library translations (~320 KB).
        localeFilters += listOf("en")
    }
    // Don't embed the dependency list that only Google can decrypt (F-Droid/IzzyOnDroid flag it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/*.version",
            "/kotlin/**",
            "/kotlin-tooling-metadata.json",
            "/DebugProbesKt.bin",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.10.6")
    implementation("androidx.compose.foundation:foundation:1.10.6")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // BouncyCastle is only used for certificate-encrypted PDFs, which PDFer doesn't open.
        // Leaving it out saves space and drops an outdated crypto library.
        exclude(group = "org.bouncycastle")
    }
    // Same ML Kit API in both editions; only where the model lives differs.
    "standardImplementation"("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    "standardImplementation"("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")
    "offlineImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "offlineImplementation"("com.google.mlkit:text-recognition-devanagari:16.0.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

/**
 * Drops PDFBox data files that PDFer's code paths never read (about 1.2 MB).
 * Predefined CJK CMaps are only read when PDFBox parses existing CJK fonts (text extraction,
 * rendering, form appearances); PDFer only needs Identity-H for the OCR text layer.
 */
abstract class PrunePdfboxAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun prune() {
        fs.sync {
            from(input)
            into(output)
            exclude { it.path.startsWith("com/tom_roush/fontbox/resources/cmap/") && !it.name.startsWith("Identity-") }
            exclude("com/tom_roush/fontbox/resources/unicode/Scripts.txt")
            exclude("com/tom_roush/pdfbox/resources/text/BidiMirroring.txt")
        }
    }
}

/** Copies the repo's license, privacy policy and notices into assets/legal/ for the About screen. */
abstract class CopyLegalAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(sources)
            into(output.dir("legal"))
        }
    }
}

val copyLegalAssets = tasks.register<CopyLegalAssets>("copyLegalAssets") {
    sources.from(rootProject.files("LICENSE", "LICENSE-EXCEPTION.md", "PRIVACY.md", "THIRD_PARTY_NOTICES.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalAssets, CopyLegalAssets::output)
        val prune = tasks.register<PrunePdfboxAssets>("prune${variant.name.replaceFirstChar(Char::uppercase)}PdfboxAssets")
        variant.artifacts.use(prune)
            .wiredWithDirectories(PrunePdfboxAssets::input, PrunePdfboxAssets::output)
            .toTransform(SingleArtifact.ASSETS)
    }
    // Real phones are ARM. The x86 copies of the OCR engine (~23 MB) only matter for emulators,
    // so release builds of the offline edition leave them out. Debug builds keep them for testing.
    onVariants(selector().withFlavor("ocr" to "offline").withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
    }
}
