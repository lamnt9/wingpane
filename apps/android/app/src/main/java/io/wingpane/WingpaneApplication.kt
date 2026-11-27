package io.wingpane

import android.app.Application
import io.wingpane.net.WingpaneClient
import io.wingpane.proto.Mode

/** Lazily holds the single current client across Activity recreation. */
class ClientHolder<T : Any>(factory: () -> T) {
    private val create = factory
    private var instance: T? = null
    val client: T
        @Synchronized get() = instance ?: create().also { instance = it }

    @Synchronized fun clear() { instance = null }
}

class WingpaneApplication : Application() {
    val clientHolder = ClientHolder { WingpaneClient() }
    @Volatile var currentMode: Mode = Mode.ExtendedDisplay
}
