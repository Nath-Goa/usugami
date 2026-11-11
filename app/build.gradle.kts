import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

// Release signing: CI passes the keystore through USUGAMI_* environment variables; a local build reads
// the git-ignored keystore.properties. Without either, release APKs are left unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingValue(env: String, key: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)

android {
    namespace = "io.github.nath_goa.usugami"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nath_goa.usugami"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 16
        versionName = "1.6.5"
    }

    signingConfigs {
        val storePath = signingValue("USUGAMI_KEYSTORE_PATH", "storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("USUGAMI_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("USUGAMI_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("USUGAMI_KEY_PASSWORD", "keyPassword")
                enableV1Signing = false // only needed below Android 7.0; minSdk is 8.0
                enableV2Signing = true
                enableV3Signing = true // allows rotating the key later
            }
        }
    }

    // Three editions, all signed with the same key.
    // standard ("Usugami", Usugami.apk): text recognition with Google ML Kit through Google Play
    //   services, which downloads the models. The only edition with Google code.
    // ocr ("Usugami OCR", Usugami-ocr.apk): open-source text recognition built in; no Google libraries.
    //   The same app ID as standard, so either one installs over the other.
    // lite ("Usugami Lite", Usugami-lite.apk): no text recognition and no Google libraries. Its own
    //   app ID (io.github.nath_goa.usugami.lite) and launcher name, so it is a separate app that
    //   installs next to the others (F-Droid lists it as an app of its own).
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
            buildConfigField("String", "EDITION", "\"standard\"")
            buildConfigField("boolean", "HAS_OCR", "true")
            proguardFile("proguard-standard.pro")
            // ML Kit pulls in AppCompat, whose own keep rule for SearchView would keep ~200 KB of
            // unused AppCompat code and resources. The app is pure Compose and never uses it.
            optimization { keepRules { ignoreFrom("androidx.appcompat:appcompat") } }
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            buildConfigField("String", "EDITION", "\"lite\"")
            buildConfigField("boolean", "HAS_OCR", "false")
        }
        create("ocr") {
            dimension = "edition"
            buildConfigField("String", "EDITION", "\"ocr\"")
            buildConfigField("boolean", "HAS_OCR", "true")
            // Phones are ARM; x86_64 is only for emulators, and release builds leave it out (below).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Usugami comes in English, French, Spanish and Hindi (also listed in res/xml/locales_config.xml);
        // this drops the libraries' 80+ other translations (~300 KB).
        localeFilters += listOf("en", "fr", "es", "hi")
    }
    // The language can be switched inside the app, so an app bundle must not split languages off.
    bundle {
        language { enableSplit = false }
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
        // BouncyCastle is only used for certificate-encrypted PDFs, which Usugami doesn't open.
        // Leaving it out saves space and drops an outdated crypto library.
        exclude(group = "org.bouncycastle")
    }
    // Only the standard edition has Google libraries: ML Kit's text recognition, whose models
    // Google Play services downloads.
    "standardImplementation"("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    "standardImplementation"("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")
    // Usugami OCR's built-in engine: ncnn and its JNI bridge, built from source (see :ocr-native).
    "ocrImplementation"(project(":ocr-native"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
    // Compose's test rule drives the screens a frame at a time, for the tests that tap faster than the
    // screen updates (MultiClickDeviceTest and the editors' multi-click tests). The same version as
    // the app's Compose, which the tests run against.
    //noinspection GradleDependency
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.6")
    // The Espresso it brings can't inject input on recent Android versions.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

/**
 * Drops asset files that a build never reads.
 * - PDFBox data files that Usugami's code paths never read (about 1.2 MB). Predefined CJK CMaps
 *   are only read when PDFBox parses existing CJK fonts (text extraction, rendering, form
 *   appearances); Usugami only needs Identity-H for the OCR text layer. BidiMirroring.txt (7 KB)
 *   stays: PDFTextStripper's static initializer crashes without it, and the device tests use
 *   PDFTextStripper to check the OCR text layer.
 * - With [withoutOcrFont] (Usugami Lite, which has no text recognition), the OCR text layer's font.
 */
abstract class PruneAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:Input
    abstract val withoutOcrFont: Property<Boolean>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun prune() {
        val dropOcrFont = withoutOcrFont.get()
        fs.sync {
            from(input)
            into(output)
            exclude { it.path.startsWith("com/tom_roush/fontbox/resources/cmap/") && !it.name.startsWith("Identity-") }
            exclude("com/tom_roush/fontbox/resources/unicode/Scripts.txt")
            // fonts/LICENSE is the OCR font's license (SIL OFL).
            if (dropOcrFont) exclude("fonts/UsugamiOcr-Regular.ttf", "fonts/LICENSE")
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
// The notices inside Google's libraries, which only the standard edition contains.
val copyGoogleNotices = tasks.register<CopyLegalAssets>("copyGoogleNotices") {
    sources.from(rootProject.files("third_party/google-notices.txt"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalAssets, CopyLegalAssets::output)
        if (variant.productFlavors.contains("edition" to "standard")) {
            variant.sources.assets?.addGeneratedSourceDirectory(copyGoogleNotices, CopyLegalAssets::output)
        }
        val prune = tasks.register<PruneAssets>("prune${variant.name.replaceFirstChar(Char::uppercase)}Assets") {
            withoutOcrFont.set(variant.productFlavors.contains("edition" to "lite"))
        }
        variant.artifacts.use(prune)
            .wiredWithDirectories(PruneAssets::input, PruneAssets::output)
            .toTransform(SingleArtifact.ASSETS)
    }
    // Real phones are ARM. The x86_64 copies of the OCR engine only matter for emulators, so release
    // builds of the OCR edition leave them out (arm64-v8a and armeabi-v7a stay). Debug builds keep
    // them for testing.
    onVariants(selector().withFlavor("edition" to "ocr").withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
    }
}
