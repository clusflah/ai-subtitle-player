package com.google.common.util.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

interface ListenableFuture<V> : Future<V> {
    fun addListener(listener: Runnable, executor: Executor)
}

object Futures {
    private val VOID_FUTURE: ListenableFuture<Void?> = object : ListenableFuture<Void?> {
        override fun addListener(listener: Runnable, executor: Executor) {
            executor.execute(listener)
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
    }

    @JvmStatic
    fun immediateVoidFuture(): ListenableFuture<Void?> = VOID_FUTURE
}
