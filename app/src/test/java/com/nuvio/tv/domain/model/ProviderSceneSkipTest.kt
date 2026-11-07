package com.nuvio.tv.domain.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderSceneSkipTest {
    private fun info(cuts: String = """{"start":["10.25","30"],"end":["12.75","35"]}""", enabled: String = "1") =
        JSONObject("""{"nb":"21","duration":"100","parent_skipping":"$enabled","skippingDurations":$cuts,"introSkipping":[{"start":"0","end":"8"}]}""")

    @Test fun `provider timings use seconds with precision and exclude intro`() {
        val data = ProviderSceneSkip.fromSource("cinemana", "21", info())!!
        assertEquals(listOf(ProviderSceneRange(10250,12750),ProviderSceneRange(30000,35000)),data.ranges)
        assertEquals(100000L,data.durationMs)
    }
    @Test fun `source flag is authoritative and malformed arrays are not paired`() {
        assertTrue(ProviderSceneSkip.fromSource("cee","21",info(enabled="0"))!!.ranges.isEmpty())
        assertTrue(ProviderSceneSkip.fromSource("cee","21",info("""{"start":[10,20],"end":[15]}"""))!!.ranges.isEmpty())
    }
    @Test fun `missing source flag still accepts valid edition timings`() {
        val source = info().apply { remove("parent_skipping") }
        val data = ProviderSceneSkip.fromSource("cinemana", "21", source)!!
        assertEquals(2, data.ranges.size)
    }

    @Test fun `explicit false source flag disables scene ranges`() {
        assertTrue(
            ProviderSceneSkip.fromSource("cee", "21", info(enabled = "false"))!!
                .ranges.isEmpty()
        )
    }

    @Test fun `invalid and nonfinite ranges are rejected and overlaps merged`() {
        val d=ProviderSceneSkip.fromSource("cee","21",info("""{"start":[-1,"NaN",12,10,99,40],"end":[2,12,20,15,200,30]}"""))!!
        assertEquals(listOf(ProviderSceneRange(10000,20000)),d.ranges)
    }
    @Test fun `native id and provider are required and cross provider payload rejected`() {
        assertNull(ProviderSceneSkip.fromSource("cee","22",info()))
        assertNull(ProviderSceneSkip.fromSource("other","21",info()))
        val payload=ProviderSceneSkip.fromSource("cee","21",info())!!.toPayload()
        assertNotNull(ProviderSceneSkip.fromPayload(payload,"Cee"))
        assertNull(ProviderSceneSkip.fromPayload(payload,"Cinemana"))
        assertNull(ProviderSceneSkip.fromPayload(payload,"Torrentio"))
        assertNull(ProviderSceneSkip.fromPayload("bad","Cee"))
    }
    @Test fun `only enabled correctly timed playback skips with short lead`() {
        val s=ProviderSceneSkipSession(ProviderSceneSkip.fromSource("cee","21",info())!!)
        assertNull(s.nextSeek(10000,100000,false,0))
        assertNull(s.nextSeek(10000,110000,true,0))
        assertNull(s.nextSeek(9000,100000,true,0))
        assertEquals(12750L,s.nextSeek(10000,100000,true,0))
        assertNull(s.nextSeek(10300,100000,true,500))
        assertNull(s.nextSeek(13000,100000,true,1000))
        assertEquals(35000L,s.nextSeek(30000,100000,true,2000))
    }
    @Test fun `rewinding into a scene skips again and sessions do not share ranges`() {
        val d=ProviderSceneSkip.fromSource("cinemana","21",info())!!
        val s=ProviderSceneSkipSession(d)
        assertEquals(12750L,s.nextSeek(11000,100000,true,0))
        assertNull(s.nextSeek(15000,100000,true,1000))
        assertEquals(12750L,s.nextSeek(11000,100000,true,3000))
        val other=ProviderSceneSkipSession(d.copy(ranges=listOf(ProviderSceneRange(50000,60000))))
        assertNull(other.nextSeek(11000,100000,true,3000))
    }

    @Test fun `small duration differences allow scene skipping`() {
        val data = ProviderSceneSkip.fromSource("cinemana", "21", info())!!
        assertTrue(data.matchesDuration(102974))
        assertTrue(data.matchesDuration(105000))
        assertFalse(data.matchesDuration(105001))
        assertFalse(data.matchesDuration(94000))
        val session = ProviderSceneSkipSession(data)
        assertEquals(12750L, session.nextSeek(10000, 102974, true, 0))
    }

    @Test fun `scene skip starts early without skipping too early`() {
        val data = ProviderSceneSkip.fromSource("cinemana", "21", info())!!
        val session = ProviderSceneSkipSession(data)
        assertNull(session.nextSeek(8700, 100000, true, 0))
        assertEquals(12750L, session.nextSeek(10000, 100000, true, 500))
    }
}
