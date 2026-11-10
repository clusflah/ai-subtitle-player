package androidx.lifecycle

import android.app.Application
import android.content.GlobalDesktopApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

interface LifecycleObserver

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

open class Lifecycle {
    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED
    }
    enum class Event {
        ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY
    }

    private val observers = mutableListOf<LifecycleObserver>()
    var currentState: State = State.RESUMED
        private set

    open fun addObserver(observer: LifecycleObserver) {
        observers.add(observer)
        if (observer is LifecycleEventObserver) {
            observer.onStateChanged(DesktopLifecycleOwner, Event.ON_CREATE)
            observer.onStateChanged(DesktopLifecycleOwner, Event.ON_START)
            observer.onStateChanged(DesktopLifecycleOwner, Event.ON_RESUME)
        }
    }

    open fun removeObserver(observer: LifecycleObserver) {
        observers.remove(observer)
    }

    fun handleLifecycleEvent(event: Event) {
        when (event) {
            Event.ON_CREATE -> currentState = State.CREATED
            Event.ON_START -> currentState = State.STARTED
            Event.ON_RESUME -> currentState = State.RESUMED
            Event.ON_PAUSE -> currentState = State.STARTED
            Event.ON_STOP -> currentState = State.CREATED
            Event.ON_DESTROY -> currentState = State.DESTROYED
            else -> {}
        }
        observers.filterIsInstance<LifecycleEventObserver>().forEach {
            it.onStateChanged(DesktopLifecycleOwner, event)
        }
    }
}

interface LifecycleOwner {
    val lifecycle: Lifecycle get() = object : Lifecycle() {}
}

object DesktopLifecycleOwner : LifecycleOwner {
    override val lifecycle: Lifecycle = Lifecycle()
}

open class ViewModel {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    protected open fun onCleared() {}

    fun clear() {
        onCleared()
        scope.cancel()
    }
}

open class AndroidViewModel(private val app: Application = GlobalDesktopApp) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = app as T
}

val ViewModel.viewModelScope: CoroutineScope
    get() = scope

object DesktopViewModelStore {
    private val map = ConcurrentHashMap<Class<*>, ViewModel>()

    @Suppress("UNCHECKED_CAST")
    fun <T : ViewModel> getOrCreate(clazz: Class<T>): T {
        return map.getOrPut(clazz) {
            try {
                clazz.getConstructor(Application::class.java).newInstance(GlobalDesktopApp)
            } catch (_: NoSuchMethodException) {
                clazz.getDeclaredConstructor().newInstance()
            }
        } as T
    }

    fun clearAll() {
        map.values.forEach { runCatching { it.clear() } }
        map.clear()
    }
}
