package io.github.norlive.remotecontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

class Bt630Failure(message: String, val retryable: Boolean = true) : Exception(message)

class Bt630Session(
    private val scope: CoroutineScope,
    private val linkFactory: (lost: (String) -> Unit) -> Link,
    private val frame: () -> ByteArray,
    private val pauseControls: () -> Unit,
    private val stateChanged: (State) -> Unit,
    private val sent: (ByteArray) -> Unit,
) {
    interface Link {
        suspend fun open()
        suspend fun send(frame: ByteArray)
        fun close()
    }

    sealed interface State {
        data object Idle : State
        data class Connecting(val attempt: Int, val error: String?) : State
        data object Ready : State
        data class Failed(val error: String) : State
    }

    private val guard = Any()
    private var generation = 0L
    private var job: Job? = null
    private var activeLink: Link? = null

    fun start() {
        stop()
        synchronized(guard) {
            val token = ++generation
            job = scope.launch { run(token) }
        }
    }

    fun stop() {
        synchronized(guard) {
            generation++
            job?.cancel()
            job = null
            activeLink?.close()
            activeLink = null
            pauseControls()
            stateChanged(State.Idle)
        }
    }

    private fun update(token: Long, action: () -> Unit) = synchronized(guard) {
        if (generation == token) action()
    }

    private suspend fun run(token: Long) {
        var attempt = 0
        var lastError: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            attempt++
            update(token) { pauseControls(); stateChanged(State.Connecting(attempt, lastError)) }
            val loss = CompletableDeferred<String>()
            var link: Link? = null
            var stable: CompletableDeferred<Unit>? = null
            var retryable = true
            try {
                val current = linkFactory { reason -> loss.complete(reason) }
                link = current
                synchronized(guard) {
                    if (generation != token) throw CancellationException("Session replaced")
                    activeLink = current
                }
                withTimeout(10_000) {
                    coroutineScope {
                        val opening = async { current.open() }
                        select<Unit> {
                            opening.onAwait { }
                            loss.onAwait { throw Bt630Failure(it) }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                update(token) { pauseControls(); stateChanged(State.Ready) }
                coroutineScope {
                    val readyPeriod = CompletableDeferred<Unit>()
                    stable = readyPeriod
                    launch { delay(10_000); readyPeriod.complete(Unit) }
                    launch {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = frame().copyOf()
                            current.send(bytes)
                            currentCoroutineContext().ensureActive()
                            update(token) { sent(bytes) }
                            delay(20)
                        }
                    }
                    throw Bt630Failure(loss.await())
                }
            } catch (error: TimeoutCancellationException) {
                lastError = "Connection timed out (10 s)"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error.message ?: "Connection failed"
                retryable = when (error) {
                    is Bt630Failure -> error.retryable
                    is SecurityException -> false
                    else -> true
                }
            } finally {
                link?.close()
                synchronized(guard) {
                    if (generation == token && activeLink === link) activeLink = null
                }
            }
            currentCoroutineContext().ensureActive()
            update(token) { pauseControls() }
            if (stable?.isCompleted == true) attempt = 0
            if (!retryable || attempt >= 5) {
                update(token) { stateChanged(State.Failed(lastError ?: "Connection failed")) }
                return
            }
            delay(when (attempt) { 0, 1 -> 1_000L; 2 -> 2_000L; else -> 4_000L })
        }
    }
}
