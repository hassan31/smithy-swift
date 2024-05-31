/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.swift.codegen

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolReference
import software.amazon.smithy.codegen.core.SymbolWriter
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.loader.Prelude
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.traits.DeprecatedTrait
import software.amazon.smithy.model.traits.DocumentationTrait
import software.amazon.smithy.model.traits.EnumDefinition
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.swift.cod.DocumentationConverter
import software.amazon.smithy.swift.codegen.integration.SectionId
import software.amazon.smithy.swift.codegen.integration.SectionWriter
import software.amazon.smithy.swift.codegen.integration.SwiftIntegration
import software.amazon.smithy.swift.codegen.model.defaultValue
import software.amazon.smithy.swift.codegen.model.isBoxed
import software.amazon.smithy.swift.codegen.model.isBuiltIn
import software.amazon.smithy.swift.codegen.model.isGeneric
import software.amazon.smithy.swift.codegen.model.isOptional
import software.amazon.smithy.swift.codegen.model.isServiceNestedNamespace
import java.util.function.BiFunction
import kotlin.jvm.optionals.getOrNull

/**
 * Handles indenting follow on params to a function that takes several params or a builder object
 * i.e.
 * func test(param1: String, param2: String, param3: Int)
 *
 * test(param1: "hi",
 *      param2: "test",
 *      param3: 4)
 */
fun SwiftWriter.swiftFunctionParameterIndent(block: SwiftWriter.() -> Unit): SwiftWriter {
    this.indent(3)
    block(this)
    this.dedent(3)
    return this
}

fun SwiftWriter.declareSection(id: SectionId, context: Map<String, Any?> = emptyMap(), block: SwiftWriter.() -> Unit = {}): SwiftWriter {
    putContext(context)
    pushState(id.javaClass.canonicalName)
    block(this)
    popState()
    removeContext(context)
    return this
}
private fun SwiftWriter.removeContext(context: Map<String, Any?>): Unit =
    context.keys.forEach { key -> removeContext(key) }

fun SwiftWriter.customizeSection(id: SectionId, writer: SectionWriter): SwiftWriter {
    onSection(id.javaClass.canonicalName) { default ->
        require(default is String?) { "Expected Smithy to pass String for previous value but found ${default::class.java}" }
        writer.write(this, default)
    }
    return this
}

class SwiftWriter(private val fullPackageName: String, swiftImportContainer: SwiftImportContainer = SwiftImportContainer()) :
    SymbolWriter<SwiftWriter, SwiftImportContainer>(swiftImportContainer) {

    companion object {
        const val GENERATED_FILE_HEADER: String = "// Code generated by smithy-swift-codegen. DO NOT EDIT!\n\n"
        const val SWIFT_FILE_EXTENSION: String = ".swift"
    }

    class SwiftWriterFactory(private val integrations: List<SwiftIntegration> = listOf()) : Factory<SwiftWriter> {
        override fun apply(filename: String, namespace: String?): SwiftWriter {

            val moduleName = if (filename.endsWith(SWIFT_FILE_EXTENSION)) {
                filename.substring(0, filename.length - 6)
            } else {
                filename
            }

            val swiftWriter = SwiftWriter(moduleName)

            integrations.forEach { integration ->
                integration.sectionWriters.forEach { (sectionId, sectionWriter) ->
                    swiftWriter.customizeSection(sectionId) { codeWriter, previousValue ->
                        sectionWriter.write(codeWriter, previousValue)
                    }
                }
            }

            return swiftWriter
        }
    }

    fun addImport(symbol: Symbol) {
        if (symbol.isBuiltIn || symbol.isServiceNestedNamespace || symbol.namespace.isEmpty()) return
        val spiName = symbol.getProperty("spiName").getOrNull()?.toString()
        val decl = symbol.getProperty("decl").getOrNull()?.toString()
        decl?.let {
            addImport("$it ${symbol.namespace}.${symbol.name}", internalSPIName = spiName)
        } ?: run {
            addImport(symbol.namespace, internalSPIName = spiName)
        }
    }

    fun addImport(
        packageName: String,
        isTestable: Boolean = false,
        internalSPIName: String? = null,
        importOnlyIfCanImport: Boolean = false
    ) {
        importContainer.addImport(packageName, isTestable, internalSPIName, importOnlyIfCanImport)
    }

    // Adds an import statement that imports the individual type from the specified module
    // Example: addIndividualTypeImport("struct", "Foundation", "Date") -> "import struct Foundation.Date"
    fun addIndividualTypeImport(decl: SwiftDeclaration, module: String, type: String) {
        importContainer.addImport("${decl.keyword} $module.$type", false)
    }

    fun addImportReferences(symbol: Symbol, vararg options: SymbolReference.ContextOption) {
        symbol.references.forEach { reference ->
            for (option in options) {
                if (reference.hasOption(option)) {
                    addImport(reference.symbol)
                    break
                }
            }
        }
    }

    override fun toString(): String {
        val contents = super.toString()
        val imports = "${importContainer}\n\n"
        return GENERATED_FILE_HEADER + imports + contents
    }

    private class SwiftSymbolFormatter(
        val writer: SwiftWriter,
        val shouldSetDefault: Boolean,
        val shouldRenderOptional: Boolean,
    ) : BiFunction<Any, String, String> {

        override fun apply(type: Any, indent: String): String {
            when (type) {
                is Symbol -> {
                    writer.addImport(type)
                    var formatted = type.fullName
                    if (type.isBoxed() && shouldRenderOptional) {
                        formatted += "?"
                    }

                    if (type.isGeneric() && type.isOptional()) {
                        formatted = "(any $formatted)?"
                    } else if (type.isGeneric()) {
                        formatted = "any $formatted"
                    } else if (type.isOptional()) {
                        formatted = "$formatted?"
                    }

                    if (shouldSetDefault) {
                        type.defaultValue()?.let {
                            formatted += " = $it"
                        }
                    }

                    return formatted
                }
                else -> throw CodegenException("Invalid type provided for \$T. Expected a Symbol, but found `$type`")
            }
        }
    }

    /**
     * Configures the writer with the appropriate single-line doc comment and calls the [block]
     * with this writer. Any calls to `write()` inside of block will be escaped appropriately.
     * On return the writer's original state is restored.
     *
     * e.g.
     * ```
     * writer.writeSingleLineDocs() {
     *     write("This is a single-line doc comment")
     * }
     * ```
     *
     * would output
     *
     * ```
     * /// This is a single-line doc comment
     * ```
     */
    fun writeSingleLineDocs(block: SwiftWriter.() -> Unit) {
        pushState("singleLineDocs")
        setNewlinePrefix("/// ")
        block(this)
        popState()
    }

    fun writeDocs(docs: String) {
        val convertedDocs = DocumentationConverter.convert(docs)
        writeSingleLineDocs {
            write(convertedDocs)
        }
    }

    /**
     * Writes shape documentation comments if docs are present.
     */
    fun writeShapeDocs(shape: Shape) {
        shape.getTrait(DocumentationTrait::class.java).ifPresent {
            writeDocs(it.value)
        }
    }

    /*
    * Writes @available attribute if deprecated trait is present
    * */
    fun writeAvailableAttribute(model: Model?, shape: Shape) {
        var deprecatedTrait: DeprecatedTrait? = null
        if (shape.getTrait(DeprecatedTrait::class.java).isPresent) {
            deprecatedTrait = shape.getTrait(DeprecatedTrait::class.java).get()
        } else if (shape.getMemberTrait(model, DeprecatedTrait::class.java).isPresent) {
            if (shape is MemberShape) {
                if (!Prelude.isPreludeShape(shape.getTarget())) {
                    deprecatedTrait = shape.getMemberTrait(model, DeprecatedTrait::class.java).get()
                }
            } else {
                deprecatedTrait = shape.getMemberTrait(model, DeprecatedTrait::class.java).get()
            }
        }

        if (deprecatedTrait != null) {
            val messagePresent = deprecatedTrait.message.isPresent
            val sincePresent = deprecatedTrait.since.isPresent
            var message = StringBuilder()
            if (messagePresent) {
                message.append(deprecatedTrait.message.get())
            }
            if (sincePresent) {
                val conditionalSpace = if (messagePresent) " " else ""
                message.append("${conditionalSpace}API deprecated since ${deprecatedTrait.since.get()}")
            }

            if (messagePresent || sincePresent) {
                write("@available(*, deprecated, message: \"${message}\")")
            } else {
                write("@available(*, deprecated)")
            }
        }
    }

    /**
     * Writes member shape documentation comments if docs are present.
     */
    fun writeMemberDocs(model: Model, member: MemberShape) {
        member.getMemberTrait(model, DocumentationTrait::class.java).orElse(null)?.let { writeDocs(it.value) }
        member.getMemberTrait(
            model,
            RequiredTrait::class.java
        ).orElse(null)?.let { writeDocs("This member is required.") }
    }

    /**
     * Writes documentation comments for Enum Definitions if present.
     */
    fun writeEnumDefinitionDocs(enumDefinition: EnumDefinition) {
        enumDefinition.documentation.ifPresent {
            writeDocs(it)
        }
    }

    fun indent(function: () -> Unit) {
        indent()
        function()
        dedent()
    }

    init {
        trimBlankLines()
        trimTrailingSpaces()
        setIndentText("    ")
        // Default values and optionality can be configured for a symbol (See SymbolBuilder)
        // The following custom formatters will render symbols according to its configuration
        // See https://smithy.io/2.0/guides/building-codegen/generating-code.html#formatters
        putFormatter('D', SwiftSymbolFormatter(this, shouldSetDefault = true, shouldRenderOptional = true))
        putFormatter('T', SwiftSymbolFormatter(this, shouldSetDefault = false, shouldRenderOptional = true))
        putFormatter('N', SwiftSymbolFormatter(this, shouldSetDefault = false, shouldRenderOptional = false))
    }
}

enum class SwiftDeclaration(val keyword: String) {
    STRUCT("struct"),
    CLASS("class"),
    ENUM("enum"),
    PROTOCOL("protocol"),
    TYPEALIAS("typealias"),
    FUNC("func"),
    LET("let"),
    VAR("var")
}
