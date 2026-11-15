package de.doorzign.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Live, nur lesend (`GET /system`): unerreichbarer Master vor der echten Edge-Adresse (decisions 36).
 * Läuft nur mit `E2E_EDGE_URL=… E2E_EDGE_FP=… ./build.sh :api:test --tests '*AddressRaceLive*'`.
 */
class AddressRaceLiveTest {
    private val url = System.getenv("E2E_EDGE_URL").orEmpty()
    private val fp = System.getenv("E2E_EDGE_FP").orEmpty()
    private val app = AppInfo("1.0.0")

    @Test fun unerreichbarerMasterKostetNurDieKarenz() = runBlocking {
        assumeTrue("E2E_EDGE_URL/E2E_EDGE_FP nicht gesetzt", url.isNotEmpty() && fp.isNotEmpty())
        val instanceId = SystemProbe.probe(url, Trust.Pinned(setOf(fp)), app).system.instanceId
        val port = java.net.URI(url).port
        val pairing = PairingUri.Edge(listOf("https://10.255.255.1:$port", url), listOf(fp), instanceId, null)
        val t0 = System.nanoTime()
        val r = SystemProbe.probeEdge(pairing, app)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("probeEdge: ${r.origin} nach $ms ms")
        assertEquals(url, r.origin)
        assertTrue(ms in 1_000..5_000, "dauerte $ms ms")
    }
}
