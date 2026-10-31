package io.github.norlive.remotecontrol

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** One controller for the Activity and its foreground connection service. */
class RemoteApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore=ViewModelStore()
    val controller: RemoteViewModel get()=ViewModelProvider(this,ViewModelProvider.AndroidViewModelFactory(this))[RemoteViewModel::class.java]
}
