package com.figma.code.connect

import com.figma.code.connect.models.CodeConnectDocument
import com.figma.code.connect.models.CodeConnectParserCreateInput
import com.figma.code.connect.models.CodeConnectParserMessage
import com.figma.code.connect.models.CodeConnectParserParseInput
import com.figma.code.connect.models.CodeConnectPluginParserOutput
import com.figma.code.connect.models.FigmaBoolean
import com.figma.code.connect.models.FigmaEnum
import com.figma.code.connect.models.FigmaInstance
import com.figma.code.connect.models.FigmaString
import com.figma.code.connect.models.PropertyMapping
import com.figma.code.connect.models.SourceLocation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * This plugin is intended to be used in conjunction with the `figma` command line tool which
 * invokes gradle tasks by passing in input parameters and reads the output
 *
 * It can be run standalone as a Gradle task as well, but the `figma` command line tool is still
 * required in order to upload the Code Connect files to Figma.
 */
class FigmaCodeConnectPlugin : Plugin<Project> {
    private val kotlinCoreEnvironment: KotlinCoreEnvironment by lazy {
        val disposable = Disposer.newDisposable()
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun apply(project: Project) {
        /**
         * `parseCodeConnect` takes an argument `filePath` which is the path to the input JSON file and parses
         * through a set of files and finds Code Connect files. It takes a single argument,
         * `filePath`, which is the path to the input JSON file.
         * The input JSON is documented in the `CodeConnectParserInput` class.
         */
        project.tasks.register("parseCodeConnect") { task ->
            task.doLast {
                val filePath = project.findProperty("filePath") as? String

                if (!filePath.isNullOrBlank()) {
                    val json =
                        Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                            serializersModule =
                                SerializersModule {
                                    polymorphic(PropertyMapping::class) {
                                        subclass(FigmaString::class, FigmaString.serializer())
                                        subclass(FigmaBoolean::class, FigmaBoolean.serializer())
                                        subclass(FigmaInstance::class, FigmaInstance.serializer())
                                        subclass(FigmaEnum::class, FigmaEnum.serializer())
                                    }
                                }
                            prettyPrint = true
                        }

                    val parseInputFile = File(filePath)
                    val codeConnectParserParseInput =
                        json.decodeFromString<CodeConnectParserParseInput>(parseInputFile.readText())

                    val documents = mutableListOf<CodeConnectDocument>()
                    val messages = mutableListOf<CodeConnectParserMessage>()
                    val composableSourceLocations = mutableMapOf<String, SourceLocation>()

                    for (path in codeConnectParserParseInput.paths.filter { it.endsWith(".kt") }) {
                        val tempFile = File(path)
                        val file =
                            LightVirtualFile(
                                "temp_file.kt",
                                KotlinFileType.INSTANCE,
                                tempFile.readText(),
                            )
                        val ktFile =
                            PsiManager.getInstance(kotlinCoreEnvironment.project)
                                .findFile(file) as KtFile

                        val parserResult = CodeConnectParser.parseFile((ktFile))
                        documents.addAll(parserResult.docs)
                        messages.addAll(parserResult.messages)
                        // Get all the line numbers for all @Composable functions to assign a SourceLocation
                        composableSourceLocations.putAll(
                            parserResult.functionLineNumbers.mapValues { SourceLocation(file = path, line = it.value) },
                        )
                    }

                    documents.forEach { doc ->
                        doc.sourceLocation = composableSourceLocations[doc.component] ?: SourceLocation(file = "", line = 0)
                    }

                    println(json.encodeToString(CodeConnectPluginParserOutput(documents, messages)).trimIndent())
                } else {
                    throw IllegalArgumentException("filePath property is required")
                }
            }
            task.notCompatibleWithConfigurationCache(
                "This task is not compatible with configuration caching because it reads from a file path property.",
            )
        }

        /**
         * `createCodeConnect` takes an argument `filePath` which is the path to the input JSON file
         * and create a Code Connect file based on information provided about the Figma Component.
         * It takes a single argument `filePath`, which is the path to the input JSON file.
         * The input JSON is documented in the `CodeConnectParserCreateInput` class.
         */
        project.tasks.register("createCodeConnect") { task ->
            task.doLast {
                val filePath = project.findProperty("filePath") as? String

                if (!filePath.isNullOrBlank()) {
                    val json =
                        Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                            allowTrailingComma = true
                        }

                    val tempFile = File(filePath)
                    val codeConnectParserCreateInput =
                        json.decodeFromString<CodeConnectParserCreateInput>(tempFile.readText())

                    val output = CodeConnectTemplate.create(codeConnectParserCreateInput)
                    println(json.encodeToString(output))
                }
            }
            task.notCompatibleWithConfigurationCache(
                "This task is not compatible with configuration caching because it reads from a file path property.",
            )
        }
    }
}
