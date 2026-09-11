import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    // Declaring the Kotlin plugin here pins the version on the buildscript classpath for
    // every module. AGP 9 otherwise supplies its own, older Kotlin, and a module that
    // asks for a specific version fails with "already on the classpath with an unknown
    // version". The Compose stack in :manager needs the newer compiler.
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.ktfmt)
}

/** A ValueSource that executes 'git rev-list --count' to get the total commit count. */
abstract class GitCommitCountValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("git", "rev-list", "--count", "refs/remotes/origin/master")
            standardOutput = output
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0 && output.toString().isNotBlank()) {
            output.toString().trim()
        } else {
            "1"
        }
    }
}

/** A ValueSource that executes 'git tag' to get the latest version tag. */
abstract class GitLatestTagValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("git", "tag", "--list", "--sort=-v:refname")
            standardOutput = output
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0 && output.toString().isNotBlank()) {
            output.toString().lineSequence().first().removePrefix("v")
        } else {
            "1.0"
        }
    }
}

abstract class GitCommitHashValueSource : ValueSource<String, GitCommitHashValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val buildRepository: Property<String>
        val buildCommit: Property<String>
    }

    @get:Inject abstract val execOperations: ExecOperations

    private fun capture(vararg command: String): String? =
        runCatching {
                val output = ByteArrayOutputStream()
                val result = execOperations.exec {
                    commandLine(command.toList())
                    standardOutput = output
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
                if (result.exitValue != 0) null else output.toString().trim().ifBlank { null }
            }
            .getOrNull()

    private fun hostname(): String {
        val raw = capture("hostname") ?: System.getenv("HOSTNAME") ?: return "local"
        val short = raw.substringBefore('.')
        val safe = short.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return safe.ifBlank { "local" }
    }

    override fun obtain(): String {
        val head = capture("git", "rev-parse", "--short", "HEAD") ?: return "unknown"
        val repository = parameters.buildRepository.getOrElse("")
        if (repository.isBlank()) {
            val dirty = capture("git", "status", "--porcelain", "--untracked-files=no") != null
            return if (dirty) "$head+${hostname()}" else head
        }
        val pushed = parameters.buildCommit.getOrElse("").takeIf { it.isNotBlank() }
        val short = pushed?.let { capture("git", "rev-parse", "--short", it) ?: it.take(head.length) } ?: head
        return short + "-" + repository.replace('/', '-')
    }
}

val versionCodeProvider = providers.of(GitCommitCountValueSource::class.java) {}
val versionHashProvider =
    providers.of(GitCommitHashValueSource::class.java) {
        parameters.buildRepository.set(
            providers
                .environmentVariable("VECTOR_BUILD_REPOSITORY")
                .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                .orElse("")
        )
        parameters.buildCommit.set(providers.environmentVariable("VECTOR_BUILD_COMMIT").orElse(""))
    }
val versionNameProvider = providers.of(GitLatestTagValueSource::class.java) {}

val injectedPackageName = "com.android.shell"
val injectedPackageUid = 2000
val defaultManagerPackageName = "org.matrix.vector.manager"

val androidTargetSdkVersion = 37
val androidMinSdkVersion = 26
val androidBuildToolsVersion = "37.0.0"
val androidCompileSdkVersion = 37
val androidCompileNdkVersion = "29.0.14206865"
val androidSourceCompatibility = JavaVersion.VERSION_21
val androidTargetCompatibility = JavaVersion.VERSION_21

extra.set("versionCodeProvider", versionCodeProvider)
extra.set("versionHashProvider", versionHashProvider)
extra.set("versionNameProvider", versionNameProvider)
extra.set("injectedPackageName", injectedPackageName)
extra.set("injectedPackageUid", injectedPackageUid)
extra.set("defaultManagerPackageName", defaultManagerPackageName)
extra.set("androidTargetSdkVersion", androidTargetSdkVersion)
extra.set("androidMinSdkVersion", androidMinSdkVersion)
extra.set("androidBuildToolsVersion", androidBuildToolsVersion)
extra.set("androidCompileSdkVersion", androidCompileSdkVersion)
extra.set("androidCompileNdkVersion", androidCompileNdkVersion)
extra.set("androidSourceCompatibility", androidSourceCompatibility)
extra.set("androidTargetCompatibility", androidTargetCompatibility)

subprojects {
    plugins.withType(AndroidBasePlugin::class.java) {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion
            buildFeatures.buildConfig = true
            externalNativeBuild.cmake {
                version = "3.29.8+"
                buildStagingDirectory = layout.buildDirectory.get().asFile
            }
            defaultConfig.apply {
                minSdk = androidMinSdkVersion
                ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) }
                if (this is ApplicationDefaultConfig) {
                    targetSdk = androidTargetSdkVersion
                    versionCode = versionCodeProvider.get().toInt()
                    versionName = versionNameProvider.get()
                }
                val flags =
                    listOf(
                        "-DVERSION_CODE=${versionCodeProvider.get()}",
                        "-DVERSION_NAME='\"${versionNameProvider.get()}\"'",
                        "-DPHMAP_HAVE_SSE2=0",
                        "-DPHMAP_HAVE_SSSE3=0",
                    )
                val args =
                    listOf(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DVECTOR_ROOT=${rootDir.absolutePath}",
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    )
                externalNativeBuild {
                    cmake {
                        cFlags.addAll(flags)
                        cppFlags.addAll(flags)
                        arguments.addAll(args)
                    }
                }
            }
            buildTypes.getByName("release").apply {
                externalNativeBuild {
                    cmake {
                        arguments.add(
                            "-DDEBUG_SYMBOLS_PATH=${
                                layout.buildDirectory.dir("symbols").get().asFile.absolutePath
                            }"
                        )
                    }
                }
            }
            lint.apply {
                abortOnError = true
                checkReleaseBuilds = false
            }
            compileOptions.apply {
                sourceCompatibility = androidSourceCompatibility
                targetCompatibility = androidTargetCompatibility
            }
        }
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
    val vendoredJava =
        setOf(
            ":external:apache",
            ":external:axml",
            ":legacy",
            ":services:daemon-service",
            ":services:manager-service",
        )
    if (path in vendoredJava) {
        tasks.withType(JavaCompile::class.java).configureEach {
            options.compilerArgs.addAll(listOf("-nowarn", "-XDsuppressNotes"))
        }
    }
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include(
        "*.gradle.kts",
        "*/build.gradle.kts",
        "hiddenapi/*/build.gradle.kts",
        "services/*-service/build.gradle.kts",
    )
    exclude("daemon/**")
    dependsOn(":daemon:ktfmtFormat")
    dependsOn(":manager:ktfmtFormat")
    dependsOn(":xposed:ktfmtFormat")
    dependsOn(":zygisk:ktfmtFormat")
}

ktfmt { kotlinLangStyle() }
