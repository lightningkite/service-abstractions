package com.lightningkite.services.database.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR Generation Extension that injects SerializablePropertiesProvider interface into serializers.
 *
 * Strategy:
 * 1. Find all files with KSP-generated {ClassName}__serializableProperties arrays
 * 2. For each array, find the corresponding model's serializer class
 * 3. Add SerializablePropertiesProvider<ModelType> to the serializer's supertypes
 * 4. Generate getSerializablePropertiesWithDefaults() method that returns the array
 */
public class DatabaseDefaultsIrGenerationExtension : IrGenerationExtension {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // Early return check - does this module have any __serializableProperties arrays?
        val hasArrays = moduleFragment.files.any { file ->
            file.declarations.filterIsInstance<IrProperty>().any { property ->
                property.name.asString().endsWith("__serializableProperties")
            }
        }
        if (!hasArrays) return

        // First pass: collect all {ClassName}__serializableProperties arrays
        val serializablePropertiesMap = mutableMapOf<String, IrProperty>()

        moduleFragment.files.forEach { file ->
            file.declarations.filterIsInstance<IrProperty>().forEach { property ->
                val name = property.name.asString()
                if (name.endsWith("__serializableProperties")) {
                    val modelName = name.removeSuffix("__serializableProperties")
                    serializablePropertiesMap[modelName] = property
                }
            }
        }

        if (serializablePropertiesMap.isEmpty()) return

        // Get reference to SerializablePropertiesProvider interface
        val providerInterface = pluginContext.referenceClass(
            ClassId.topLevel(FqName("com.lightningkite.services.database.SerializablePropertiesProvider"))
        )
        if (providerInterface == null) {
            error("SerializablePropertiesProvider interface not found!")
        }

        // Second pass: find serializer classes and inject interface
        val processedSerializers = mutableListOf<String>()
        moduleFragment.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach { irClass ->
                processClass(irClass, serializablePropertiesMap, pluginContext, providerInterface, processedSerializers)
            }
        }

        if (processedSerializers.isEmpty() && serializablePropertiesMap.isNotEmpty()) {
            // Debug: find what classes we do have for these model names
            val classInfo = mutableListOf<String>()
            moduleFragment.files.forEach { file ->
                file.declarations.filterIsInstance<IrClass>().forEach { irClass ->
                    val className = irClass.name.asString()
                    if (serializablePropertiesMap.keys.any { className.contains(it) }) {
                        val nested = irClass.declarations.filterIsInstance<IrClass>().map { it.name.asString() }
                        classInfo.add("$className (isCompanion=${irClass.isCompanion}, nested=$nested)")
                    }
                }
            }
            error("Found ${serializablePropertiesMap.size} arrays (${serializablePropertiesMap.keys.joinToString()}) but no serializers were processed! Classes found: $classInfo")
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun processClass(
        irClass: IrClass,
        serializablePropertiesMap: Map<String, IrProperty>,
        pluginContext: IrPluginContext,
        providerInterface: org.jetbrains.kotlin.ir.symbols.IrClassSymbol,
        processedSerializers: MutableList<String>
    ) {
        val modelName = irClass.name.asString()

        // Check if this model class has a corresponding __serializableProperties array
        val propertiesArray = serializablePropertiesMap[modelName]
        if (propertiesArray != null) {
            // Look for $serializer as a direct nested class of the model
            val serializerClass = irClass.declarations.filterIsInstance<IrClass>()
                .find { it.name.asString() == "\$serializer" }

            if (serializerClass != null) {
                injectInterface(serializerClass, propertiesArray, pluginContext, providerInterface, irClass)
                processedSerializers.add(modelName)
            }
        }

        // Also check nested classes recursively (for nested model classes)
        irClass.declarations.filterIsInstance<IrClass>().forEach { nestedClass ->
            processClass(nestedClass, serializablePropertiesMap, pluginContext, providerInterface, processedSerializers)
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun injectInterface(
        serializerClass: IrClass,
        propertiesArray: IrProperty,
        pluginContext: IrPluginContext,
        providerInterface: org.jetbrains.kotlin.ir.symbols.IrClassSymbol,
        modelClass: IrClass
    ) {
        // Add SerializablePropertiesProvider<ModelType> to supertypes
        val modelType = modelClass.defaultType
        val providerType = providerInterface.typeWith(modelType)

        serializerClass.superTypes = serializerClass.superTypes + providerType

        // Get the interface method to override
        val interfaceMethod = providerInterface.functions.single {
            it.owner.name.asString() == "getSerializablePropertiesWithDefaults"
        }

        // Create getSerializablePropertiesWithDefaults() method
        val method = pluginContext.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("getSerializablePropertiesWithDefaults"),
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            // Use the interface method's return type, substituting T with modelType
            returnType = interfaceMethod.owner.returnType.substitute(
                mapOf(providerInterface.owner.typeParameters.first().symbol to modelType)
            ),
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL,
            symbol = org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false,
            isExternal = false,
            isFakeOverride = false,
            containerSource = null
        ).apply {
            parent = serializerClass
            overriddenSymbols = listOf(interfaceMethod)

            // Add dispatch receiver for instance method using reflection to bypass deprecation error
            setDispatchReceiver(this, createDispatchReceiver(pluginContext.irFactory as Any, serializerClass, this))

            // Create method body that returns the properties array by calling the getter
            val getter = propertiesArray.getter ?: return
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +irReturn(irCall(getter))
            }
        }

        serializerClass.declarations.add(method)
    }

    /**
     * Sets the dispatch receiver on a function using reflection to bypass deprecation.
     */
    private fun setDispatchReceiver(function: IrSimpleFunction, receiver: IrValueParameter) {
        val setter = function.javaClass.methods.find {
            it.name == "setDispatchReceiverParameter" && it.parameterCount == 1
        } ?: error("Could not find setDispatchReceiverParameter method")
        setter.invoke(function, receiver)
    }

    /**
     * Creates a dispatch receiver parameter using reflection to bypass Kotlin 2.2's deprecation errors.
     * Uses createValueParameter(int, int, IrDeclarationOrigin, IrParameterKind, Name, IrType, boolean, IrValueParameterSymbol, IrType, boolean, boolean, boolean)
     */
    private fun createDispatchReceiver(
        irFactory: Any,  // Use Any to avoid compile-time type checks on IrFactory
        serializerClass: IrClass,
        parent: IrSimpleFunction
    ): IrValueParameter {
        // Find the method with IrParameterKind parameter (second createValueParameter with 12 params)
        val method = irFactory.javaClass.methods.find {
            it.name == "createValueParameter" &&
            it.parameterCount == 12 &&
            it.parameterTypes.any { p -> p.simpleName == "IrParameterKind" }
        } ?: error("Could not find createValueParameter method with IrParameterKind")

        val symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl()

        // IrParameterKind.DispatchReceiver
        val parameterKindClass = Class.forName("org.jetbrains.kotlin.ir.declarations.IrParameterKind")
        val dispatchReceiver = parameterKindClass.enumConstants.find {
            it.toString() == "DispatchReceiver"
        } ?: error("Could not find DispatchReceiver in IrParameterKind")

        val result = method.invoke(
            irFactory,
            UNDEFINED_OFFSET,  // startOffset: int
            UNDEFINED_OFFSET,  // endOffset: int
            IrDeclarationOrigin.DEFINED,  // origin: IrDeclarationOrigin
            dispatchReceiver,  // kind: IrParameterKind
            Name.special("<this>"),  // name: Name
            serializerClass.defaultType,  // type: IrType
            false,  // isAssignable: boolean
            symbol,  // symbol: IrValueParameterSymbol
            null,  // varargElementType: IrType?
            false,  // isCrossinline: boolean
            false,  // isNoinline: boolean
            false   // isHidden: boolean
        ) as IrValueParameter
        result.parent = parent
        return result
    }

    private companion object {
        const val UNDEFINED_OFFSET = -1
    }
}
