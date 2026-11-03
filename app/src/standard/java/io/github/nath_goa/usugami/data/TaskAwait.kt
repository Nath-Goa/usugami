package io.github.nath_goa.usugami.data

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Waits for a Play services or ML Kit task without blocking a thread, and stops waiting as soon as
 * the coroutine is cancelled; the task itself carries on. (What kotlinx-coroutines-play-services
 * does, without the dependency.)
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Runnable::run) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
