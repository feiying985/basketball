package com.feiying.basketball.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login posts account and parses token`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"access_token":"token-1","token_type":"bearer",
                 "expires_at":"2026-12-06T02:00:00Z",
                 "user":{"id":"u1","phone":"+8613800138000","username":null,
                         "display_name":"138****8000","created_at":"2026-11-08T00:00:00Z"}}
                """.trimIndent(),
            ),
        )

        val response = api().login("13800138000", "correct-horse-battery")

        assertEquals("token-1", response.accessToken)
        assertEquals("138****8000", response.user.displayName)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/login", request.path)
        assertTrue(request.body.readUtf8().contains("\"account\":\"13800138000\""))
    }

    @Test
    fun `sms code 429 exposes retry after`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "42")
                .setBody("""{"detail":"验证码发送过于频繁，请稍后再试"}"""),
        )

        try {
            api().sendCode("13800138000", "register")
            throw AssertionError("expected AuthApiException")
        } catch (error: AuthApiException) {
            assertEquals(429, error.statusCode)
            assertEquals(42, error.retryAfterSeconds)
            assertEquals("验证码发送过于频繁，请稍后再试", error.detail)
        }
    }

    @Test
    fun `interceptor adds token and reports protected 401`() = runBlocking {
        val unauthorized = AtomicInteger(0)
        val tokenProvider = TokenProvider().apply { token = "token-1" }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider) { unauthorized.incrementAndGet() })
            .build()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"u1","phone":null,"username":"alice",
                    "display_name":"alice","created_at":"2026-11-08T00:00:00Z"}""",
            ),
        )

        api(client).me()

        val request = server.takeRequest()
        assertEquals("Bearer token-1", request.getHeader("Authorization"))

        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"登录状态已失效"}"""),
        )
        try {
            api(client).me()
        } catch (_: AuthApiException) {
            // expected
        }
        assertEquals(1, unauthorized.get())
    }

    private fun api(client: OkHttpClient = OkHttpClient()): AuthApi = AuthApi(
        json = Json { ignoreUnknownKeys = true },
        baseUrlProvider = { server.url("/").toString() },
        client = client,
    )
}
