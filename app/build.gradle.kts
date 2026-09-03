import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

val appVersion = providers.gradleProperty("ace.version").orElse("0.1.10").get()
if (!Regex("""\d+\.\d+\.\d+""").matches(appVersion)) {
    throw GradleException("ace.version must use numeric x.y.z format for Windows packaging, got: $appVersion")
}

group = "ace.app"
version = appVersion

val isWindowsHost: Boolean = System.getProperty("os.name", "").contains("Windows", ignoreCase = true)
val bridgeDir: Directory = layout.projectDirectory.dir("../bridge")
val bridgeOutputDir: Provider<Directory> = layout.buildDirectory.dir("native/bridge")
val generatedNativeResourcesDir: Provider<Directory> = layout.buildDirectory.dir("generated/nativeResources")
val generatedProfileResourcesDir: Provider<Directory> = layout.buildDirectory.dir("generated/profileResources")

val buildRawAccelBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the bundled native rawaccel_bridge.dll."

    inputs.files(fileTree(bridgeDir.asFile) {
        include("*.cpp")
        include("*.h")
        include("*.hpp")
        include("rawaccel-common/**")
    })
    outputs.file(bridgeOutputDir.map { it.file("rawaccel_bridge.dll") })

    val script = bridgeDir.file("build.ps1").asFile
    workingDir = bridgeDir.asFile

    doFirst {
        if (!isWindowsHost) {
            throw GradleException("rawaccel_bridge.dll can only be built on Windows.")
        }

        if (!script.isFile) {
            throw GradleException("Missing bridge build script: ${script.absolutePath}")
        }

        val outputDir = bridgeOutputDir.get().asFile
        outputDir.mkdirs()
        commandLine(
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.absolutePath,
            "-OutputDir",
            outputDir.absolutePath
        )
    }
}

val copyRawAccelBridge by tasks.registering(Copy::class) {
    dependsOn(buildRawAccelBridge)
    from(bridgeOutputDir.map { it.file("rawaccel_bridge.dll") })
    into(generatedNativeResourcesDir.map { it.dir("win32-x86-64") })
}

val copyDefaultProfiles by tasks.registering(Copy::class) {
    val sourceProfilesDir = layout.projectDirectory.dir("profiles")

    outputs.file(generatedProfileResourcesDir.map { it.file("profiles/index.txt") })

    from(sourceProfilesDir) {
        include("*.json")
    }
    into(generatedProfileResourcesDir.map { it.dir("profiles") })

    doLast {
        val outputDir = generatedProfileResourcesDir.get().dir("profiles").asFile
        outputDir.mkdirs()

        val profileNames = sourceProfilesDir.asFile
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        outputDir.resolve("index.txt").writeText(
            profileNames.joinToString(System.lineSeparator()).let {
                if (it.isEmpty()) it else it + System.lineSeparator()
            }
        )
    }
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResourcesDir)
        resources.srcDir(generatedProfileResourcesDir)
    }
}

tasks.named("processResources") {
    dependsOn(copyRawAccelBridge)
    dependsOn(copyDefaultProfiles)
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)

    // Core JNA for loading the bridge DLL.
    implementation("net.java.dev.jna:jna:5.14.0")
    // JNA platform adds User32/Kernel32/Psapi bindings for global hotkeys
    // and foreground-window detection on Windows. Safe to ship cross-platform:
    // non-Windows guards prevent any linkage errors on Linux/macOS.
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Keep every Jackson component on the same patched release. The explicit
    // core dependency also prevents security scanners from reporting an older
    // transitive version supplied by another library.
    implementation(enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.1"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("jpackage.app-version", appVersion)
}

compose.desktop {
    application {
        mainClass = "rawaccel.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Ace"
            packageVersion = appVersion
            description = "Advanced mouse acceleration profile control"
            vendor = "Ace"

            windows {
                menuGroup = "Ace"
                iconFile.set(project.file("src/main/resources/icons/Ace.ico"))
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "10b76623-26d9-47da-b73a-e345e2bb137c"
            }
        }
    }
}
