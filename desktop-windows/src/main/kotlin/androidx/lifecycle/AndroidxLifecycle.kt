package androidx.lifecycle

import android.app.Application
import android.content.GlobalDesktopApp

object DesktopLifecycleOwner : LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.RESUMED
    }
}

open class AndroidViewModel(private val app: Application = GlobalDesktopApp) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = app as T
}

object DesktopViewModelStore {
    val store = ViewModelStore()

    @Suppress("UNCHECKED_CAST")
    fun <T : ViewModel> getOrCreate(clazz: Class<T>): T {
        val key = clazz.canonicalName ?: clazz.name
        val existing = store[key]
        if (existing != null) return existing as T
        val vm = try {
            clazz.getConstructor(Application::class.java).newInstance(GlobalDesktopApp)
        } catch (_: NoSuchMethodException) {
            clazz.getDeclaredConstructor().newInstance()
        }
        store.put(key, vm)
        return vm as T
    }

    fun clearAll() {
        store.clear()
    }
}
