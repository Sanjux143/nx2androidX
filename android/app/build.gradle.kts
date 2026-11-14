// The phone app: nx2android's porter, running on the phone itself.
//
// The app is a shell: it unpacks the environment (the rootfs from the nixport payload, the toolchain and the
// tool's own tree) into its files folder, and runs the porter inside it. The UI is Android's own.
plugins {
    id("com.android.application")
    // The Compose compiler: from Kotlin 2.0 it is a plugin of its own
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// The payload (rootfs, toolchain, the tool's tree) is built by packaging/android-apk/build_assets.py and staged
// outside the source tree: it is a few hundred megabytes and not committed.
val assetsDir = rootProject.layout.projectDirectory.dir("../packaging/android-apk/.cache/assets")

// The Vulkan driver loader (libadrenotools.so), built by packaging/android-apk/build_adrenotools.py and staged
// outside the source tree as well. It is the app's own native library: the host dlopens it by name, and the
// Android linker looks a bare name up in LD_LIBRARY_PATH, in the caller's DT_RUNPATH and in the namespace's
// default library paths — the app's native library folder is the only one of those the app controls.
val jniLibsDir = rootProject.layout.projectDirectory.dir("../packaging/android-apk/.cache/jniLibs")

// The app's own signing key: a keystore of the project's, so that every build of the app (this machine, CI, the
// release) carries the same signature and installs over the one before it. Android refuses an update whose
// signature differs (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and the debug keystore of a machine is that machine's:
// two builds from two machines can never replace each other. The keystore is not in the repository: it lives in
// ~/.android/nx2android.keystore (or wherever NX2A_KEYSTORE points), and CI gets it from the secret
// NX2A_KEYSTORE_B64. Without it the build falls back to the machine's debug key, which is good enough for a
// build nobody installs over another.
fun envOf(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
val nx2aKeystore = File(envOf("NX2A_KEYSTORE") ?: "${System.getProperty("user.home")}/.android/nx2android.keystore")

// -PnxLsfg=false: the host the app builds on the device is built without the frame generation (the lsfg/ folder):
// the host is then smaller and the frame generation reports that it is not in this build. The default is the current
// behaviour: the code is linked. The license is the same either way (GPL-3.0-or-later, android/NOTICE.md).
val nxLsfg = (project.findProperty("nxLsfg") as String?)?.toBooleanStrictOrNull() ?: true

// One revision source for the APK and its bundled porter.
val playerApiRevision = Regex("(?m)^PLAYER_API_REVISION = ([1-9][0-9]*)$")
    .find(rootProject.file("../porter/revision.py").readText())!!.groupValues[1].toInt()
val revisionSources = layout.buildDirectory.dir("generated/playerApiRevision")
val generatePlayerApiRevision by tasks.registering {
    inputs.file(rootProject.file("../porter/revision.py"))
    outputs.dir(revisionSources)
    doLast {
        val target = revisionSources.get().file("com/nx/android/domain/PlayerApiRevision.java").asFile
        target.parentFile.mkdirs()
        target.writeText("package com.nx.android.domain;\n\npublic final class PlayerApiRevision { public static final int CURRENT = $playerApiRevision; private PlayerApiRevision() {} }\n")
    }
}
tasks.named("preBuild").configure { dependsOn(generatePlayerApiRevision) }

android {
    namespace = "com.nx.android"
    compileSdk = 37

    signingConfigs {
        if (nx2aKeystore.isFile) {
            create("nx2android") {
                storeFile = nx2aKeystore
                storePassword = envOf("NX2A_KEYSTORE_PASSWORD") ?: "nx2android"
                keyAlias = envOf("NX2A_KEYSTORE_ALIAS") ?: "nx2android"
                keyPassword = envOf("NX2A_KEY_PASSWORD") ?: envOf("NX2A_KEYSTORE_PASSWORD") ?: "nx2android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.nx.android"
        minSdk = 26
        // SDK 29 from the platform enforces W^X for app data: the guest programs under files/rootfs could not
        // be exec'd at all. The app is not on Google Play (it ships as an APK from the project's releases).
        targetSdk = 28
        // The version the release's tag carries, without the "v": v0.3.0-alpha02 -> 0.3.0-alpha02. The update
        // check compares the two, so the app can tell which release is newer than the one running.
        versionCode = 10
        versionName = "0.3.0-alpha10"
        ndk { abiFilters += listOf("arm64-v8a") }
        // -PnxLsfg=false: the host the app builds is built without the frame generation (the lsfg/ folder): the host
        // is then smaller and the frame generation reports that it is not in this build. The default is the current
        // behaviour: the code is linked. The license is the same either way (GPL-3.0-or-later, android/NOTICE.md).
        buildConfigField("boolean", "NX_LSFG", nxLsfg.toString())
    }

    sourceSets {
        getByName("main") {
            java.srcDir(revisionSources.get().asFile)
            // The payload (rootfs, toolchain, the tool's tree): built by packaging/android-apk/build_assets.py.
            // -PnoPayload leaves it out: a quick build of the app itself, for a phone that already has the
            // environment unpacked (the unpacked tree is in the app's own folder, so it survives the update).
            if (!project.hasProperty("noPayload") && assetsDir.asFile.exists()) {
                assets.directories.add(assetsDir.asFile.absolutePath)
            }
            // The Vulkan driver loader, in the app's own native library folder: the host dlopens it by name and
            // that folder is where the linker looks (jniLibs.useLegacyPackaging keeps it extracted there).
            if (jniLibsDir.asFile.exists()) {
                jniLibs.srcDir(jniLibsDir.asFile.absolutePath)
            }
        }
    }

    // The payload is big: no compression of the assets that are already compressed (the rootfs tarball), and
    // the whole thing is a single big blob the app unpacks on the first start.
    androidResources {
        noCompress += listOf("gz", "so", "zip")
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    lint {
        // targetSdk = 28 is deliberate: SDK 29 and later enforce W^X for app data, and the guest programs
        // under files/rootfs could not be exec'd at all. This check only says the target is old - it is
        // not an error here, and it is the one check that stands between the tree and a release build.
        disable += "ExpiredTargetSdkVersion"
    }

    // The screen is Jetpack Compose (Material 3).
    buildFeatures {
        compose = true
        // The frame generation's switch (NX_LSFG) is read by the app: the host is built on the device when a game is
        // ported, and the copyleft component of that build is chosen here (see android/NOTICE.md).
        buildConfig = true
    }

    buildTypes {
        // The project's own key when it is there (the machine's, or the one CI restores from the secret), the
        // machine's debug key otherwise: the app is not on Google Play, and a build nobody installs over another
        // needs no key of its own.
        getByName("debug") { signingConfig = signingConfigs.findByName("nx2android") ?: signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("nx2android") ?: signingConfigs.getByName("debug")
        }
    }

    dependencies {
        implementation(platform("androidx.compose:compose-bom:2026.09.00"))
        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.ui:ui-tooling-preview")
        // The preview runtime: the IDE's Compose Preview renders the @Preview functions with it (debug only,
        // it never goes into the APK)
        debugImplementation("androidx.compose.ui:ui-tooling")
        implementation("androidx.compose.material3:material3")
        implementation("androidx.compose.material:material-icons-extended")
        implementation("androidx.activity:activity-compose:1.13.0")
        implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
        implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("com.google.dagger:hilt-android:2.60.1")
        ksp("com.google.dagger:hilt-compiler:2.60.1")
        // adb's wireless debugging pairing needs the TLS session's keying material, which the
        // platform's Conscrypt does not export: the app pairs with itself to switch off Android's
        // child-process limit (data/PhantomFix.kt)
        implementation("org.conscrypt:conscrypt-android:2.7.0")
        testImplementation("junit:junit:4.13.2")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        // the porter's progress events and the data pack's manifest are JSON: the JVM tests parse them with
        // the real org.json (the android.jar the tests run against only has the stubs)
        testImplementation("org.json:json:20240303")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Keep the most important package directions executable while the legacy feature/platform calls are moved
// behind routes and domain contracts in follow-up changes. The allowlist is intentionally file-specific:
// moving a call or adding a new one requires an explicit architecture decision.
val legacyFeatureDataImports = setOf(
    "feature/builds/BuildsScreen.kt|com.nx.android.data.AppLog",
    "feature/environment/EnvChecklistDialog.kt|com.nx.android.data.PhantomFix",
    "feature/environment/PhantomFixDialog.kt|com.nx.android.data.PhantomFix",
    "feature/gamedetail/GameDetailScreen.kt|com.nx.android.data.GameIcon",
    "feature/gamedetail/GameDetailScreen.kt|com.nx.android.data.GameLog",
    "feature/gamedetail/GameDetailScreen.kt|com.nx.android.data.Player",
    "feature/gamedetail/GameDetailScreen.kt|com.nx.android.data.SaveShare",
    "feature/gamedetail/GameDetailScreen.kt|com.nx.android.data.StorageVolumes",
    "feature/settings/SettingsScreen.kt|com.nx.android.data.AppLocale",
    "feature/settings/SettingsScreen.kt|com.nx.android.data.LosslessDll",
)

val checkArchitectureBoundaries by tasks.registering {
    group = "verification"
    description = "Checks core domain dependency direction and prevents new feature-to-data imports."
    doLast {
        val sourceRoot = file("src/main/java/com/nx/android")
        val domainRoot = file("$sourceRoot/domain")
        val forbiddenDomainImports = Regex("^import (android\\.|androidx\\.|com\\.nx\\.android\\.(data|feature|appstate|game|app)\\.)")
        val domainViolations = domainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { source ->
            source.readLines().mapIndexedNotNull { index, line ->
                if (forbiddenDomainImports.containsMatchIn(line)) "${source.relativeTo(sourceRoot)}:${index + 1}: $line" else null
            }
        }.toList()

        val featureRoot = file("$sourceRoot/feature")
        val featureDataImports = featureRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { source ->
            source.readLines().mapNotNull { line ->
                val match = Regex("^import (com\\.nx\\.android\\.data\\..+)$").find(line) ?: return@mapNotNull null
                "${source.relativeTo(sourceRoot)}|${match.groupValues[1]}"
            }
        }.toSet()
        val unapprovedFeatureImports = featureDataImports - legacyFeatureDataImports
        val staleAllowlistEntries = legacyFeatureDataImports - featureDataImports

        check(domainViolations.isEmpty()) {
            "Domain imports must stay platform/UI/data independent:\n${domainViolations.joinToString("\n")}"
        }
        check(unapprovedFeatureImports.isEmpty()) {
            "New feature-to-data imports need an explicit migration or allowlist decision:\n${unapprovedFeatureImports.sorted().joinToString("\n")}"
        }
        check(staleAllowlistEntries.isEmpty()) {
            "Remove migrated imports from legacyFeatureDataImports:\n${staleAllowlistEntries.sorted().joinToString("\n")}"
        }
    }
}

tasks.named("check") { dependsOn(checkArchitectureBoundaries) }
