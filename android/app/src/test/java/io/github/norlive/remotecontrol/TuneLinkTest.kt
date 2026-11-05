package io.github.norlive.remotecontrol

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneLinkTest {
    @Test fun `open refuses a read only bridge`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"symbol":"g_rc","address":536871328,"write_enabled":false}"""))
            val link = TuneLink(server.url("/").toString(), "", {})
            val error = assertThrows(Bt630Failure::class.java) { runBlocking { link.open() } }
            assertEquals("桥接器处于只读模式，请用 --allow-write 启动", error.message)
            assertEquals(false, error.retryable)
        }
    }

    @Test fun `open reports where g_rc lives`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"symbol":"g_rc","address":536871328,"write_enabled":true}"""))
            val notes = mutableListOf<String>()
            runBlocking { TuneLink(server.url("/").toString(), "", { notes += it }).open() }
            assertEquals(listOf("g_rc @0x200001A0 · 链路正常"), notes)
            assertEquals("/api/health", server.takeRequest().path)
        }
    }

    @Test fun `send posts the same frame with the token`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"verified":true,"frames":1}"""))
            val frame = ControlProtocol.encode(ControlState(enabled = true, lx = 321, slider1 = 700), 5)
            val notes = mutableListOf<String>()
            runBlocking { TuneLink(server.url("/").toString(), "secret", { notes += it }).send(frame) }
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/frame", request.path)
            assertEquals("secret", request.getHeader("X-Token"))
            assertArrayEquals(frame, request.body.readByteArray())
            assertEquals(listOf("已写入 1 帧 · 回读一致"), notes)
        }
    }

    @Test fun `send surfaces a write that did not stick`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"verified":false,"error":"读回不一致"}"""))
            val notes = mutableListOf<String>()
            runBlocking {
                TuneLink(server.url("/").toString(), "", { notes += it })
                    .send(ControlProtocol.encode(ControlState(stopped = true), 0))
            }
            assertTrue(notes.single().startsWith("写入未生效：读回不一致"))
        }
    }

    @Test fun `wrong token is not retryable`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"error":"令牌错误"}"""))
            val error = assertThrows(Bt630Failure::class.java) { runBlocking { TuneLink(server.url("/").toString(), "bad", {}).open() } }
            assertEquals(false, error.retryable)
        }
    }
}