package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

interface InjektFactory {
    fun <R : Any> getInstance(forType: Type): R
}

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

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> protected constructor() : TypeReference<T> {
    override val type: Type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
}
