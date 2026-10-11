package io.github.norlive.remotecontrol

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClassicTransportTest {
    private val uuid=UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val device=mock(BluetoothDevice::class.java)
    private val compatible=mock(BluetoothSocket::class.java)
    private val secure=mock(BluetoothSocket::class.java)
    private fun sockets() {
        `when`(device.createInsecureRfcommSocketToServiceRecord(uuid)).thenReturn(compatible)
        `when`(device.createRfcommSocketToServiceRecord(uuid)).thenReturn(secure)
    }
    @Test fun compatibleConnectionSendsAndClosesWithoutSecondAttempt()=runBlocking {
        sockets()
        val bytes=ByteArrayOutputStream()
        `when`(compatible.outputStream).thenReturn(bytes)
        val link=ClassicTransport()
        link.open(device);link.send(byteArrayOf(1,2,3));link.close()
        assertArrayEquals(byteArrayOf(1,2,3),bytes.toByteArray())
        verify(compatible).connect();verify(compatible).close()
        verify(device,never()).createRfcommSocketToServiceRecord(uuid)
    }
    @Test fun failedCompatibleSocketClosesBeforeSecureRetry()=runBlocking {
        sockets();doThrow(IOException("auth failed")).`when`(compatible).connect()
        val link=ClassicTransport()
        link.open(device)
        val order=inOrder(compatible,secure)
        order.verify(compatible).connect();order.verify(compatible).close();order.verify(secure).connect()
        link.close();verify(secure).close()
    }
    @Test fun bothFailuresCloseSocketsAndReportFailure()=runBlocking {
        sockets()
        doThrow(IOException("compat failed")).`when`(compatible).connect()
        doThrow(IOException("secure failed")).`when`(secure).connect()
        try { ClassicTransport().open(device);fail("must fail") } catch(e: IOException) {
            assertTrue(e.message.orEmpty().contains("HC-05"))
        }
        verify(compatible).close();verify(secure).close()
    }
    @Test fun attemptTimeoutClosesBlockedSocketAndTriesSecure()=runBlocking {
        sockets()
        val released=CountDownLatch(1)
        doAnswer { assertTrue(released.await(3,TimeUnit.SECONDS));throw IOException("closed") }.`when`(compatible).connect()
        doAnswer { released.countDown();null }.`when`(compatible).close()
        val link=ClassicTransport(attemptTimeoutMs=40)
        link.open(device);verify(secure).connect();link.close()
    }
    @Test fun userCloseDuringConnectDoesNotStartFallback()=runBlocking {
        sockets()
        val started=CountDownLatch(1);val released=CountDownLatch(1)
        doAnswer { started.countDown();assertTrue(released.await(3,TimeUnit.SECONDS));throw IOException("closed") }.`when`(compatible).connect()
        doAnswer { released.countDown();null }.`when`(compatible).close()
        val link=ClassicTransport()
        val pending=async(Dispatchers.Default) { runCatching { link.open(device) } }
        assertTrue(started.await(3,TimeUnit.SECONDS));link.close()
        assertTrue(pending.await().isFailure)
        verify(device,never()).createRfcommSocketToServiceRecord(uuid)
    }
}
