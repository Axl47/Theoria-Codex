package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.mihonhost.MihonHost
import com.theoriacodex.mihonhost.answering
import com.theoriacodex.mihonhost.cannedResponse
import com.theoriacodex.sources.http.BrowserCookieStore
import java.io.IOException
import com.theoriacodex.mihonhost.assertFailsWith
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HostInterceptorsTest {
    private val request = Request.Builder().url("https://example.test/").build()

    @Test
    fun `a non-IO exception later in the chain is wrapped in IOException`() {
        val cause = IllegalStateException("boom")
        val client = clientOf(UncaughtExceptionInterceptor(), Interceptor { throw cause })

        val failure = assertFailsWith<IOException> { client.newCall(request).execute() }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `the client extensions rebuild carries the interceptor names they require, in Mihon's order`() {
        assertEquals(
            listOf("UncaughtExceptionInterceptor", "UserAgentInterceptor", "CloudflareInterceptor"),
            MihonHost.extensionClient(NoCookies, { _, _ -> false }).interceptors.map { it.javaClass.simpleName },
        )
    }

    @Test
    fun `an IOException passes through as it is`() {
        val client = clientOf(UncaughtExceptionInterceptor(), Interceptor { throw IOException("offline") })

        val failure = assertFailsWith<IOException> { client.newCall(request).execute() }

        assertEquals("offline", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `the default agent is added when the request names none`() {
        assertEquals("test-agent", agentSeenFor(request))
    }

    @Test
    fun `the agent a request names is kept`() {
        assertEquals("custom/1", agentSeenFor(request.newBuilder().header("User-Agent", "custom/1").build()))
    }

    private fun agentSeenFor(request: Request): String? {
        var seen: String? = null
        val client = clientOf(
            UserAgentInterceptor { "test-agent" },
            answering { sent ->
                seen = sent.header("User-Agent")
                cannedResponse(sent)
            },
        )
        client.newCall(request).execute().close()
        return seen
    }

    private object NoCookies : BrowserCookieStore {
        override fun cookieHeader(url: String): String? = null

        override fun setCookie(url: String, setCookie: String) = Unit

        override fun flush() = Unit

        override fun expire(url: String, name: String?) = Unit
    }

    private fun clientOf(vararg interceptors: Interceptor) = OkHttpClient.Builder().apply { interceptors.forEach(::addInterceptor) }.build()
}
