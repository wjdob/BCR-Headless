/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-FileCopyrightText: 2023 Patryk Miś
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.VariantOutputConfiguration
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.json.JSONObject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

buildscript {
    dependencies {
        classpath(libs.jgit)
        classpath(libs.json)
    }
}

typealias VersionTriple = Triple<String?, Int, ObjectId>

fun describeVersion(git: Git): VersionTriple {
    // jgit doesn't provide a nice way to get strongly-typed objects from its `describe` command
    val describeStr = git.describe().setLong(true).call()

    return if (describeStr != null) {
        val pieces = describeStr.split('-').toMutableList()
        val commit = git.repository.resolve(pieces.removeLast().substring(1))
        val count = pieces.removeLast().toInt()
        val tag = pieces.joinToString("-")

        Triple(tag, count, commit)
    } else {
        val log = git.log().call().iterator()
        val head = log.next()
        var count = 1

        while (log.hasNext()) {
            log.next()
            ++count
        }

        Triple(null, count, head.id)
    }
}

fun getVersionCode(major: Int, minor: Int, patch: Int, commitCount: Int): Int {
    // The headless rebuild intentionally uses its own version line instead of
    // inheriting the fork history's release tags. That keeps published versions
    // honest about this project's architectural reset. The visible version name
    // is plain semver, while the low digits of versionCode still advance with
    // the current revision so local update testing keeps working.
    return major * 1_000_000 + minor * 10_000 + patch * 100 + commitCount.coerceAtMost(99)
}

fun getVersionName(major: Int, minor: Int, patch: Int): String {
    return "$major.$minor.$patch"
}

val git = Git.open(File(rootDir, ".git"))!!
val gitVersionTriple = describeVersion(git)
val projectVersionMajor = 1
val projectVersionMinor = 0
val projectVersionPatch = 0
val gitVersionCode = getVersionCode(
    projectVersionMajor,
    projectVersionMinor,
    projectVersionPatch,
    gitVersionTriple.second,
)
val gitVersionName = getVersionName(projectVersionMajor, projectVersionMinor, projectVersionPatch)

val projectUrl = providers.gradleProperty("projectUrl")
    .orElse("https://github.com/wjdob/BCR-Headless")
    .get()
val releaseMetadataBranch = providers.gradleProperty("releaseMetadataBranch")
    .orElse("main")
    .get()
val moduleId = "bcr.headless"
val moduleName = "BCR Headless"
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull
val hasCustomReleaseSigning = !releaseKeystore.isNullOrBlank()

val extraDir = layout.buildDirectory.map { it.dir("extra") }
android {
    namespace = "com.chiller3.bcr"

    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // The helper APK is no longer installed as a package. It only provides
        // code for the headless daemon launched from the module directory via
        // app_process, so a stable internal-only id is sufficient here.
        applicationId = "com.chiller3.bcr.headless"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Keep the source link, but do not embed a copy of the repository in the
        // release APK. Shipping the full tree as an asset makes static scanning
        // much easier for apps looking for root/module-specific strings.
        buildConfigField("String", "PROJECT_URL_AT_COMMIT",
            "\"${projectUrl}/tree/${gitVersionTriple.third.name}\"")

        buildConfigField("String", "PROVIDER_AUTHORITY",
            "APPLICATION_ID + \".provider\"")
        resValue("string", "provider_authority", "$applicationId.provider")

        // Keep capability flags in one place so the legacy app code continues
        // to compile even though the release artifact now only ships the
        // headless helper.
        buildConfigField("boolean", "HAS_PRIVILEGED_CALL_CAPTURE", "false")
        buildConfigField("boolean", "REQUIRES_PHONE_STATE", "false")
        buildConfigField("boolean", "SUPPORTS_RECORD_RULES", "false")
        buildConfigField("boolean", "SUPPORTS_TELECOM_APPS", "false")
        buildConfigField("boolean", "SUPPORTS_RECORD_DIALING_STATE", "false")
    }
    androidResources {
        generateLocaleConfig = true
    }
    signingConfigs {
        create("release") {
            // Fall back to the debug signing key when no dedicated release key is
            // configured. This keeps local "release-mode" validation possible so
            // we can test a non-debuggable, minified APK inside the Magisk module
            // without requiring the maintainer's private signing material.
            storeFile = if (hasCustomReleaseSigning) { File(releaseKeystore!!) } else { null }
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSPHRASE")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSPHRASE")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = if (hasCustomReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_21)
        targetCompatibility(JavaVersion.VERSION_21)
    }
    buildFeatures {
        buildConfig = true
        resValues = true
        viewBinding = true
    }
    lint {
        // The translations are always going to lag behind new strings being
        // added to values/strings.xml
        disable += "MissingTranslation"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kudzu)
    implementation(libs.libphonenumber)
    implementation(libs.material)
    testImplementation(libs.junit)
}

androidComponents.onVariants { variant ->
    if (variant.buildType != "release") {
        return@onVariants
    }

    val capitalized = variant.name.uppercaseFirstChar()
    val variantDir = extraDir.map { it.dir(variant.name) }
    val variantOutput = variant.outputs.first {
        it.outputType == VariantOutputConfiguration.OutputType.SINGLE
    }
    val variantVersionCode = variantOutput.versionCode
    val variantVersionName = variantOutput.versionName
    val variantApkFiles = variant.artifacts.get(SingleArtifact.APK).map {
        variant.artifacts.getBuiltArtifactsLoader().load(it)!!.elements.map { element ->
            element.outputFile
        }
    }

    val moduleProp = tasks.register("moduleProp${capitalized}") {
        inputs.property("projectUrl", projectUrl)
        inputs.property("releaseMetadataBranch", releaseMetadataBranch)
        inputs.property("moduleId", moduleId)
        inputs.property("moduleName", moduleName)
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("variant.name", variant.name)
        inputs.property("variantVersionCode", variantVersionCode)
        inputs.property("variantVersionName", variantVersionName)

        val outputFile = variantDir.map { it.file("module.prop") }
        outputs.file(outputFile)

        doLast {
            // Keep the Magisk module metadata intentionally minimal. Some apps perform
            // broad scans of installed packages/modules, so avoid shipping
            // package-install details or legacy system-app identifiers here.
            val props = LinkedHashMap<String, String>()
            props["id"] = moduleId
            props["name"] = moduleName
            props["version"] = "v${variantVersionName.get()}"
            props["versionCode"] = variantVersionCode.get().toString()
            props["author"] = "wjdob"
            props["description"] = "Headless call recorder rebuild with module WebUI"
            props["updateJson"] = "${projectUrl}/raw/${releaseMetadataBranch}/app/magisk/updates/${variant.name}/info.json"

            outputFile.get().asFile.writeText(
                props.map { "${it.key}=${it.value}" }.joinToString("\n"))
        }
    }

    tasks.register<Zip>("zip${capitalized}") {
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("moduleId", moduleId)
        inputs.property("variant.name", variant.name)
        inputs.property("variantVersionName", variantVersionName)
        inputs.files(variantApkFiles)

        archiveFileName.set("${rootProject.name}-${variantVersionName.get()}-headless.zip")
        // Force instantiation of old value or else this will cause infinite recursion
        destinationDirectory.set(destinationDirectory.dir(variant.name).get())

        // Make the zip byte-for-byte reproducible (note that the APK is still not reproducible)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        from(moduleProp.map { it.outputs })
        from(variantApkFiles) {
            // Keep the helper APK inside the module directory. The headless
            // daemon loads it with app_process, but nothing is installed into
            // PackageManager or mounted into /system anymore.
            rename { "bcr-headless.apk" }
            into("tools")
        }

        val magiskDir = File(projectDir, "magisk")

        for (script in arrayOf("update-binary", "updater-script")) {
            from(File(magiskDir, script)) {
                into("META-INF/com/google/android")
            }
        }

        for (script in arrayOf(
            "post-fs-data.sh",
            "service.sh",
            "action.sh",
            "module_common.sh",
            "customize.sh",
        )) {
            from(File(magiskDir, script)) {
                filePermissions {
                    unix("755")
                }
            }
        }
        from(File(magiskDir, "skip_mount"))
        from(File(magiskDir, "webroot")) {
            into("webroot")
        }

        from(File(rootDir, "LICENSE"))
        from(File(rootDir, "README.md"))
    }

    tasks.register("updateJson${capitalized}") {
        inputs.property("gitVersionTriple.first", gitVersionTriple.first)
        inputs.property("projectUrl", projectUrl)
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("variant.name", variant.name)
        inputs.property("variantVersionCode", variantVersionCode)
        inputs.property("variantVersionName", variantVersionName)

        val magiskDir = File(projectDir, "magisk")
        val updatesDir = File(magiskDir, "updates")
        val variantUpdateDir = File(updatesDir, variant.name)
        val jsonFile = File(variantUpdateDir, "info.json")

        outputs.file(jsonFile)

        doLast {
            if (gitVersionTriple.second != 0) {
                throw IllegalStateException("The release tag must be checked out")
            }

            val root = JSONObject()
            root.put("version", variantVersionName.get())
            root.put("versionCode", variantVersionCode.get())
            root.put("zipUrl", "${projectUrl}/releases/download/${gitVersionTriple.first}/${rootProject.name}-${variantVersionName.get()}-headless.zip")
            root.put("changelog", "${projectUrl}/raw/${gitVersionTriple.first}/app/magisk/updates/${variant.name}/changelog.txt")

            jsonFile.writer().use {
                root.write(it, 4, 0)
            }
        }
    }
}

data class LinkRef(val type: String, val number: Int, val user: String?) : Comparable<LinkRef> {
    override fun compareTo(other: LinkRef): Int = compareValuesBy(
        this,
        other,
        { it.type },
        { it.number },
        { it.user },
    )

    override fun toString(): String = buildString {
        append('[')
        append(type)
        append(" #")
        append(number)
        if (user != null) {
            append(" @")
            append(user)
        }
        append(']')
    }
}

fun checkBrackets(line: String) {
    var expectOpening = true

    for (c in line) {
        if (c == '[' || c == ']') {
            if (c == '[' != expectOpening) {
                throw IllegalArgumentException("Mismatched brackets: $line")
            }

            expectOpening = !expectOpening
        }
    }

    if (!expectOpening) {
        throw IllegalArgumentException("Missing closing bracket: $line")
    }
}

fun updateChangelogLinks(baseUrl: String) {
    val file = File(rootDir, "CHANGELOG.md")
    val regexStandaloneLink = Regex("\\[([^\\]]+)\\](?![\\(\\[])")
    val regexAutoLink = Regex("(Issue|PR) #(\\d+)(?: @([\\w-]+))?")
    val links = hashMapOf<LinkRef, String>()
    var skipRemaining = false
    val changelog = mutableListOf<String>()

    file.useLines { lines ->
        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            if (!skipRemaining) {
                checkBrackets(line)
                val matches = regexStandaloneLink.findAll(line)

                for (linkMatch in matches) {
                    val linkText = linkMatch.groupValues[1]
                    val match = regexAutoLink.matchEntire(linkText)
                    require(match != null) { "Invalid link format: $linkText" }

                    val ref = match.groupValues[0]
                    val type = match.groupValues[1]
                    val number = match.groupValues[2].toInt()
                    val user = match.groups[3]?.value

                    val link = when (type) {
                        "Issue" -> {
                            require(user == null) { "$ref should not have a username" }
                            "$baseUrl/issues/$number"
                        }
                        "PR" -> {
                            require(user != null) { "$ref should have a username" }
                            "$baseUrl/pull/$number"
                        }
                        else -> throw IllegalArgumentException("Unknown link type: $type")
                    }

                    // #0 is used for examples only
                    if (number != 0) {
                        links[LinkRef(type, number, user)] = link
                    }
                }

                if ("Do not manually edit the lines below" in line) {
                    skipRemaining = true
                }

                changelog.add(line)
            }
        }
    }

    for ((ref, link) in links.entries.sortedBy { it.key }) {
        changelog.add("$ref: $link")
    }

    changelog.add("")

    file.writeText(changelog.joinToString("\n"))
}

fun updateChangelog(version: String?, replaceFirst: Boolean) {
    val file = File(rootDir, "CHANGELOG.md")
    val expected = if (version != null) { "### Version $version" } else { "### Unreleased" }

    val changelog = mutableListOf<String>().apply {
        // This preserves a trailing newline, unlike File.readLines()
        addAll(file.readText().lineSequence())
    }

    val index = changelog.indexOfFirst { it.startsWith("### ") }
    if (index == -1) {
        changelog.addAll(0, listOf(expected, ""))
    } else if (changelog[index] != expected) {
        if (replaceFirst) {
            changelog[index] = expected
        } else {
            changelog.addAll(index, listOf(expected, ""))
        }
    }

    file.writeText(changelog.joinToString("\n"))
}

fun updateMagiskChangelog(gitRef: String) {
    File(File(File(File(projectDir, "magisk"), "updates"), "release"), "changelog.txt")
        .writeText("The changelog can be found at: [`CHANGELOG.md`]($projectUrl/blob/$gitRef/CHANGELOG.md).\n")
}

tasks.register("changelogUpdateLinks") {
    doLast {
        updateChangelogLinks(projectUrl)
    }
}

tasks.register("changelogPreRelease") {
    val version = project.findProperty("releaseVersion")

    doLast {
        updateChangelog(version!!.toString(), true)
        updateMagiskChangelog("v$version")
    }
}

tasks.register("changelogPostRelease") {
    doLast {
        updateChangelog(null, false)
        updateMagiskChangelog(releaseMetadataBranch)
    }
}

tasks.register("preRelease") {
    dependsOn("changelogUpdateLinks")
    dependsOn("changelogPreRelease")
}

tasks.register("postRelease") {
    dependsOn("updateJsonRelease")
    dependsOn("changelogPostRelease")
}
