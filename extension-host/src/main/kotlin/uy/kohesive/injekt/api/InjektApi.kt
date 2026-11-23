// Binary-compatible stand-in for the four Injekt classes extension binaries call (uy.kohesive.injekt 1.16).
package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

interface InjektFactory {
    fun <R : Any> getInstance(forType: Type): R
}

/** A fixed map from type to instance, as Mihon's own read-only registrar is. Only the host runtime binds. */
class InjektScope : InjektFactory {
    private val bindings = ConcurrentHashMap<Type, Any>()

    internal fun <T : Any> bind(type: Class<T>, instance: T) {
        bindings[type] = instance
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R =
        bindings[forType] as R? ?: throw InjektionException("$forType is not provided to extensions by this host")
}

class InjektionException(message: String) : RuntimeException(message)

/** Extensions subclass this anonymously so the requested type survives erasure: `object : FullTypeReference<Json>() {}`. */
abstract class FullTypeReference<T> protected constructor() {
    val type: Type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
}
