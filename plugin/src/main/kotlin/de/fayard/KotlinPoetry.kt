package de.fayard

import com.squareup.kotlinpoet.*
import java.util.*

internal val LibsClassName = "Libs"
internal val VersionsClassName = "Versions"


/**
 * We don't want to use meaningless generic libs like Libs.core
 *
 * Found many inspiration for bad libs here https://developer.android.com/jetpack/androidx/migrate
 * **/
val MEANING_LESS_NAMES: List<String> = listOf(
    "common", "core", "core-testing", "testing", "runtime", "extensions",
    "compiler", "migration", "db", "rules", "runner", "monitor", "loader",
    "media", "print", "io", "media", "collection", "gradle", "android"
)

val INITIAL_GITIGNORE = """
.gradle/
build/
"""

val INITIAL_SETTINGS = ""

val GRADLE_KDOC = """
See issue 19: How to update Gradle itself?
https://github.com/jmfayard/buildSrcVersions/issues/19
"""

val KDOC_LIBS = """
    Generated by https://github.com/jmfayard/buildSrcVersions

    Update this file with
      `$ ./gradlew buildSrcVersions`
    """.trimIndent()

val KDOC_VERSIONS = """
    Generated by https://github.com/jmfayard/buildSrcVersions

    Find which updates are available by running
        `$ ./gradlew buildSrcVersions`
    This will only update the comments.

    YOU are responsible for updating manually the dependency version.
    """.trimIndent()


const val INITIAL_BUILD_GRADLE_KTS = """
plugins {
    `kotlin-dsl`
}
repositories {
    mavenCentral()
}
        """


@Suppress("LocalVariableName")
fun kotlinpoet(versions: List<Dependency>, gradleConfig: GradleConfig): KotlinPoetry {

    val versionsProperties: List<PropertySpec> = versions
        .distinctBy { it.versionName }
        .map(Dependency::generateVersionProperty)

    val libsProperties: List<PropertySpec> = versions
        .distinctBy { it.escapedName }
        .map(Dependency::generateLibsProperty)

    val gradleProperties: List<PropertySpec> = listOf(
        constStringProperty("gradleLatestVersion", gradleConfig.current.version, CodeBlock.of(GRADLE_KDOC)),
        constStringProperty("gradleCurrentVersion", gradleConfig.running.version)
    )

    val Versions: TypeSpec = TypeSpec.objectBuilder("Versions")
        .addKdoc(KDOC_VERSIONS)
        .addProperties(versionsProperties + gradleProperties)
        .build()


    val Libs = TypeSpec.objectBuilder("Libs")
        .addKdoc(KDOC_LIBS)
        .addProperties(libsProperties)
        .build()


    val LibsFile = FileSpec.builder("", LibsClassName)
        .addType(Libs)
        .build()

    val VersionsFile = FileSpec.builder("", VersionsClassName)
        .addType(Versions)
        .build()

    return KotlinPoetry(Libs = LibsFile, Versions = VersionsFile)

}

fun Dependency.generateVersionProperty(): PropertySpec {
    return constStringProperty(
        name = versionName,
        initializer = CodeBlock.of("%S %L", version, versionInformation())
    )
}

fun Dependency.versionInformation(): String {
    val comment = when {
        version == "none" -> "// No version. See buildSrcVersions#23"
        available == null -> ""
        else -> available.displayComment()
    }
    return if (comment.length + versionName.length + version.length > 70) {
            '\n' + comment
        } else {
            comment
        }
}

fun AvailableDependency.displayComment(): String {
    val newerVersion: String? = when {
        release.isNullOrBlank().not() -> release
        milestone.isNullOrBlank().not() -> milestone
        integration.isNullOrBlank().not() -> integration
        else -> null
    }
    return  if (newerVersion == null) "// $this" else """// available: "$newerVersion""""
}



fun Dependency.generateLibsProperty(): PropertySpec {
    // https://github.com/jmfayard/buildSrcVersions/issues/23
    val libValue = when(version) {
        "none" -> CodeBlock.of("%S", "$group:$name")
        else -> CodeBlock.of("%S + Versions.%L", "$group:$name:", versionName)
    }

    val libComment = when {
        projectUrl == null -> null
         else -> CodeBlock.of("%L", this.projectUrl)
    }

    return constStringProperty(
        name = escapedName,
        initializer = libValue,
        kdoc = libComment
    )

}


fun BuildSrcVersionsTask.Companion.parseGraph(
    graph: DependencyGraph,
    useFdqnByDefault: List<String>
): List<Dependency> {

    val dependencies: List<Dependency> = graph.current + graph.exceeded + graph.outdated + graph.unresolved

    val map = mutableMapOf<String, Dependency>()
    for (d: Dependency in dependencies) {
        val key = escapeName(d.name)
        val fdqnName = d.fdqnName()


        if (key in useFdqnByDefault) {
            d.escapedName = fdqnName
        } else if (map.containsKey(key)) {
            d.escapedName = fdqnName

            // also use FDQN for the dependency that conflicts with this one
            val other = map[key]!!
            other.escapedName = other.fdqnName()
        } else {
            map[key] = d
            d.escapedName = key
        }
    }
    return dependencies.orderDependencies().findCommonVersions()
}

fun Dependency.fdqnName(): String = escapeName("${group}_${name}")


fun List<Dependency>.orderDependencies(): List<Dependency> {
    return this.sortedBy { it.gradleNotation() }
}

fun List<Dependency>.findCommonVersions(): List<Dependency> {
    val map = groupBy { d -> d.group }
    for (deps in map.values) {
        val groupTogether = deps.size > 1  && deps.map { it.version }.distinct().size == 1

        for (d in deps) {
            d.versionName = if (groupTogether) escapeName(d.group) else d.escapedName
        }
    }
    return this
}

fun constStringProperty(name: String, initializer: CodeBlock, kdoc: CodeBlock? = null) =
    PropertySpec.builder(name, String::class)
        .addModifiers(KModifier.CONST)
        .initializer(initializer)
        .apply {
            if (kdoc != null) addKdoc(kdoc)
        }.build()


fun constStringProperty(name: String, initializer: String, kdoc: CodeBlock? = null) =
    constStringProperty(name, CodeBlock.of("%S", initializer), kdoc)


fun escapeName(name: String): String {
    val escapedChars = listOf('-', '.', ':')
    return buildString {
        for (c in name) {
            append(if (c in escapedChars) '_' else c.toLowerCase())
        }
    }
}
