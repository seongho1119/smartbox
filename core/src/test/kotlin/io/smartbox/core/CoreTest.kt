package io.smartbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

class EventDetectorTest {
    private fun s(t: Long, v: Float, acc: Float? = null, gear: Gear = Gear.D) =
        VehicleSample(t, v, gear, ignitionOn = true, acceleratorPct = acc)

    @Test fun steadyDrivingHasNoEvent() {
        val d = EventDetector()
        for (i in 0..50) assertNull(d.onSample(s(i * 100L, 50f + (i % 2))))
    }

    @Test fun hardBrakeDetected() {
        val d = EventDetector()
        var ev: DrivingEvent? = null
        // 60km/h에서 1초 만에 30km/h로: 약 -8.3 m/s²
        for (i in 0..10) ev = ev ?: d.onSample(s(i * 100L, 60f - i * 3f))
        assertNotNull(ev)
        assertEquals(EventType.HARD_BRAKE, ev!!.type)
    }

    @Test fun hardAccelDetected() {
        val d = EventDetector()
        var ev: DrivingEvent? = null
        for (i in 0..10) ev = ev ?: d.onSample(s(i * 100L, i * 2f))
        assertEquals(EventType.HARD_ACCEL, ev?.type)
    }

    @Test fun pedalSurgeAtLowSpeed() {
        val d = EventDetector()
        assertNull(d.onSample(s(0, 5f, acc = 10f)))
        assertEquals(EventType.PEDAL_SURGE, d.onSample(s(100, 5f, acc = 95f))?.type)
    }

    @Test fun cooldownSuppressesDuplicates() {
        val d = EventDetector(cooldownMs = 10_000)
        var count = 0
        for (i in 0..30) if (d.onSample(s(i * 100L, 90f - i * 3f)) != null) count++
        assertEquals(1, count)
    }

    @Test fun ignitionOffIgnored() {
        val d = EventDetector()
        for (i in 0..10) assertNull(d.onSample(VehicleSample(i * 100L, 60f - i * 5f, Gear.D, false)))
    }
}

class ClockSyncTest {
    @Test fun usesShortestRoundTrip() {
        val c = ClockSync()
        // 차량 시계가 1000ms 앞서 있다. 첫 측정은 지연이 커서 부정확하다.
        c.addMeasurement(t0 = 0, vehicleTime = 1400, t1 = 200)  // offset 1300 (부정확)
        c.addMeasurement(t0 = 1000, vehicleTime = 2010, t1 = 1020) // offset 1000
        c.addMeasurement(t0 = 2000, vehicleTime = 3300, t1 = 2400) // rtt 큼 → 무시
        assertEquals(1000, c.offsetMs)
        assertEquals(5000, c.vehicleToPhone(6000))
        assertTrue(c.synced)
    }
}

class SegmentStoreTest {
    private fun seg(n: Int) = SegmentStore.Segment("s$n", n * 20_000L, (n + 1) * 20_000L, 100)

    @Test fun evictsOldestUnprotected() {
        val st = SegmentStore(maxUnprotectedBytes = 300)
        val removed = (0..4).flatMap { st.add(seg(it)) }
        assertEquals(listOf("s0", "s1"), removed)
    }

    @Test fun protectsPastAndFutureSegments() {
        val st = SegmentStore(maxUnprotectedBytes = 200)
        st.add(seg(0)); st.add(seg(1))
        // 이벤트 시각 30초 → 10초~50초 구간: s0, s1(이미 녹화), s2(앞으로) 보호
        st.protect("e1", 10_000, 50_000)
        st.add(seg(2))
        assertFalse(st.isComplete("e1", 45_000))
        assertTrue(st.isComplete("e1", 60_000))
        (3..8).forEach { st.add(seg(it)) }
        assertEquals(listOf("s0", "s1", "s2"), st.segmentsFor("e1").map { it.name })
        assertTrue(st.all.any { it.name == "s0" })
    }
}

class MotionDetectorTest {
    @Test fun detectsChange() {
        val w = 320; val h = 240
        val m = MotionDetector()
        val a = ByteArray(w * h) { 50 }
        val b = a.copyOf().also { for (y in 0 until 120) for (x in 0 until 160) it[y * w + x] = 200.toByte() }
        assertEquals(0f, m.score(a, w, h))
        assertEquals(0f, m.score(a, w, h))
        assertTrue(m.isMotion(m.score(b, w, h)))
    }
}

class ProtocolTest {
    @Test fun pairingRoundTrip() {
        val p = PairingInfo("192.168.43.1", 8787, "a b&c")
        assertEquals(p, PairingInfo.parse(p.toUri()))
        assertNull(PairingInfo.parse("https://example.com"))
    }

    @Test fun keyValueRoundTrip() {
        val m = KeyValue.decode(KeyValue.encode(mapOf("recording" to true, "motion" to 0.5f, "x" to null)))
        assertEquals(mapOf("recording" to "true", "motion" to "0.5"), m)
    }

    @Test fun mjpegRoundTrip() {
        val out = ByteArrayOutputStream()
        Mjpeg.writeFrame(out, byteArrayOf(1, 2, 3))
        Mjpeg.writeFrame(out, byteArrayOf(4, 5))
        val r = Mjpeg.Reader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf<Byte>(1, 2, 3), r.next()!!.toList())
        assertEquals(listOf<Byte>(4, 5), r.next()!!.toList())
        assertNull(r.next())
    }
}

class MiniHttpServerTest {
    @Test fun servesRequests() {
        val port = ServerSocket(0).use { it.localPort }
        val server = MiniHttpServer(port) { req, res ->
            when (req.path) {
                "/time" -> res.text("t=${req.query["x"]}")
                "/mjpeg" -> res.open(Mjpeg.CONTENT_TYPE) { out -> repeat(2) { Mjpeg.writeFrame(out, byteArrayOf(it.toByte())) } }
            }
        }
        server.start()
        try {
            assertEquals("t=1 2", Http.get("http://127.0.0.1:$port/time?x=1%202"))
            assertEquals("t=null", Http.post("http://127.0.0.1:$port/time"))
            val (c, s) = Http.openStream("http://127.0.0.1:$port/mjpeg")
            val r = Mjpeg.Reader(s)
            assertEquals(0.toByte(), r.next()!![0])
            assertEquals(1.toByte(), r.next()!![0])
            c.disconnect()
            val ex = runCatching { Http.get("http://127.0.0.1:$port/nope") }.exceptionOrNull()
            assertTrue(ex?.message?.contains("404") == true)
        } finally {
            server.stop()
        }
    }
}
