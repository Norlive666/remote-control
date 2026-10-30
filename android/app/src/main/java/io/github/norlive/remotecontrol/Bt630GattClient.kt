package io.github.norlive.remotecontrol

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.CoroutineScope

class Bt630GattClient(
    context: Context,
    device: BluetoothDevice,
    scope: CoroutineScope,
    lost: (String) -> Unit,
    received: (ByteArray) -> Unit,
) : Bt630Session.Link {
    override suspend fun open() {}
    override suspend fun send(frame: ByteArray) {}
    override fun close() {}
}
