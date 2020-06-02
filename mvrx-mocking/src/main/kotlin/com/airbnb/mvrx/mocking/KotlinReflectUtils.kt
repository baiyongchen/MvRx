package com.airbnb.mvrx.mocking

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.functions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions

internal val KClass<*>.isEnum: Boolean
    get() {
        return getIfReflectionSupported {
            this::class.java.isEnum || isSubclassOf(Enum::class)
        } == true
    }

/**
 * True if this is a java primitive type.
 * This will return false for any nullable types, since those can't be represented as java primitives.
 */
internal val KClass<*>.isPrimitiveType: Boolean
    get() {
        return getIfReflectionSupported {
            javaPrimitiveType
        } != null
    }

internal val KClass<*>.isKotlinClass: Boolean
    get() {
        return this.java.declaredAnnotations.any {
            it.annotationClass.qualifiedName == "kotlin.Metadata"
        }
    }

internal val KClass<*>.isObjectInstance: Boolean
    get() {
        return getIfReflectionSupported {
            objectInstance
        } != null
    }

/**
 * Some objects cannot be access with Kotlin reflection, and throw UnsupportedOperationException.
 *
 * The error message is:
 * "This class is an internal synthetic class generated by the Kotlin compiler.
 * It's not a Kotlin class or interface, so the reflection library has no idea what declarations does it have."
 */
internal fun <T> getIfReflectionSupported(block: () -> T): T? {
    return try {
        block()
    } catch (e: UnsupportedOperationException) {
        null
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> KClass<T>.copyMethod(): KFunction<T> =
    this.memberFunctions.first { it.name == "copy" } as KFunction<T>

/** Call the copy function of the Data Class receiver. The params are a map of parameter name to value. */
internal fun <T : Any> T.callCopy(vararg params: Pair<String, Any?>): T {
    val paramMap = params.associate { it }
    return this::class.copyMethod().callNamed(paramMap, self = this)
}

/**
 * Invoke a function with the given parameter names.
 *
 * @param params Mapping of parameter name to parameter value
 * @param self The receiver of the function
 * @param extSelf The extension receiver of the function
 */
internal fun <R> KFunction<R>.callNamed(
    params: Map<String, Any?>,
    self: Any? = null,
    extSelf: Any? = null
): R {
    val map = params.mapTo(ArrayList()) { (key, value) ->
        val param = parameters.firstOrNull { it.name == key }
            ?: throw IllegalStateException("No parameter named '$key' found on copy function for '${this.returnType.classifier}'")
        param to value
    }

    if (self != null) map += instanceParameter!! to self
    if (extSelf != null) map += extensionReceiverParameter!! to extSelf
    return callBy(map.toMap())
}

/** Helper to call a function reflectively. */
internal inline fun <reified T> Any.call(functionName: String, vararg args: Any?): T {
    val function = this::class.functions.find { it.name == functionName }
        ?: error("No function found with name $functionName in class ${this.javaClass.name}")

    return function.call(this, *args) as T
}