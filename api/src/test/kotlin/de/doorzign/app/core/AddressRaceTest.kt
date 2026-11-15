package de.doorzign.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
/** Parallele Adressprüfung aus dem Verbindungslink (decisions 36). */
class AddressRaceTest {
    private fun fail(code: String = ApiException.NETWORK): Nothing = throw ApiException(0, code)

    @Test fun masterGewinntInnerhalbDerKarenz() = runTest {
        val r = SystemProbe.raceAddresses(3, 1_000, { i -> delay(if (i == 0) 800 else 100); i })
        assertEquals(0, r)
        assertEquals(800, currentTime)
    }

    @Test fun ohneMasterGewinntErsteGueltigeAntwort() = runTest {
        // Unerreichbare Adresse vorn (z. B. Docker-IP): nicht 20 s warten.
        var cancelled = false
        val r = SystemProbe.raceAddresses(3, 1_000, { i ->
            when (i) { 0 -> { delay(20_000); fail(ApiException.TIMEOUT) }; 1 -> { delay(300); fail() }; else -> { delay(500); i } }
        }, cancelAll = { cancelled = true })
        assertEquals(2, r)
        assertEquals(1_000, currentTime)
        assertTrue(cancelled)
    }

    @Test fun nachKarenzGewinntWerZuerstAntwortet() = runTest {
        val r = SystemProbe.raceAddresses(2, 1_000, { i -> delay(if (i == 0) 1_500 else 1_200); i })
        assertEquals(1, r)
        assertEquals(1_200, currentTime)
    }

    @Test fun masterFehlerSofortDannAndereAdresse() = runTest {
        val r = SystemProbe.raceAddresses(2, 1_000, { i -> if (i == 0) fail() else { delay(200); i } })
        assertEquals(1, r)
        assertEquals(200, currentTime)
    }

    @Test fun instanzfehlerAndererAdresseBlockiertNicht() = runTest {
        val r = SystemProbe.raceAddresses(2, 1_000, { i -> if (i == 0) { delay(1_500); i } else fail(ApiException.INSTANCE_MISMATCH) })
        assertEquals(0, r)
    }

    @Test fun alleScheiternZertifikatsfehlerVorNetzwerk() = runTest {
        val e = assertFailsWith<ApiException> {
            SystemProbe.raceAddresses(3, 1_000, { i -> if (i == 2) fail(ApiException.CERTIFICATE_MISMATCH) else fail(ApiException.TIMEOUT) })
        }
        assertEquals(ApiException.CERTIFICATE_MISMATCH, e.code)
        val e2 = assertFailsWith<ApiException> {
            SystemProbe.raceAddresses(2, 1_000, { i -> fail(if (i == 0) ApiException.TIMEOUT else ApiException.NETWORK) })
        }
        assertEquals(ApiException.TIMEOUT, e2.code)
        val e3 = assertFailsWith<ApiException> {
            SystemProbe.raceAddresses(3, 1_000, { i ->
                fail(listOf(ApiException.TIMEOUT, ApiException.INSTANCE_MISMATCH, ApiException.CERTIFICATE_MISMATCH)[i])
            })
        }
        assertEquals(ApiException.CERTIFICATE_MISMATCH, e3.code)
        val e4 = assertFailsWith<ApiException> {
            SystemProbe.raceAddresses(2, 1_000, { i -> fail(if (i == 0) ApiException.TIMEOUT else ApiException.INSTANCE_MISMATCH) })
        }
        assertEquals(ApiException.INSTANCE_MISMATCH, e4.code)
    }

    @Test fun haengenderEchterAufrufWirdAbgebrochen() = runBlocking {
        val hang = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); start() }
        val ok = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() }
        val client = OkHttpClient.Builder().readTimeout(java.time.Duration.ofSeconds(20)).build()
        val urls = listOf(hang.url("/"), ok.url("/"))
        val t0 = System.nanoTime()
        val r = SystemProbe.raceAddresses(2, 300, { i ->
            apiCall { client.newCall(Request.Builder().url(urls[i]).build()).execute().use { it.body!!.string() } }
        }, cancelAll = { client.dispatcher.cancelAll() })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("ok", r)
        assertTrue(ms < 5_000, "dauerte $ms ms")
        hang.shutdown(); ok.shutdown()
    }
}
