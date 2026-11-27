package com.markwhite.harbor

import com.markwhite.harbor.data.FirmwareEntry
import com.markwhite.harbor.data.FirmwareStore
import com.markwhite.harbor.data.InstalledPackage
import com.markwhite.harbor.data.LocalSort
import com.markwhite.harbor.data.Recordings
import com.markwhite.harbor.data.SaveStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class LibraryStorageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun installedPackageLegacyJsonDeserializesWithDefaults() {
        val legacy = """
            [
              {"sha":"abc123","catalogId":"felucca","name":"Felucca","version":"1.6.1","size":524288,"addedAt":1700000000000,"url":"https://example.com/f.fwsc"}
            ]
        """.trimIndent()
        val parsed = json.decodeFromString(ListSerializer(InstalledPackage.serializer()), legacy)
        assertEquals(1, parsed.size)
        val p = parsed.first()
        assertEquals("Felucca 1.6.1", p.title)
        assertEquals(0L, p.lastPlayedAt)
        assertFalse(p.favorite)
        assertEquals("", p.note)
    }

    @Test
    fun sortAndFilterOnDevicePinsFavoritesAndSearchesNotes() {
        val a = InstalledPackage("1", "felucca", "Felucca", "1.6", 500_000, addedAt = 100, lastPlayedAt = 500, favorite = false, note = "warm FM pad")
        val b = InstalledPackage("2", "shrub", "SHRUB", "1.0", 900_000, addedAt = 200, lastPlayedAt = 200, favorite = true, note = "use 80 MHz")
        val c = InstalledPackage("3", "local", "Custom", "", 200_000, addedAt = 300, lastPlayedAt = 900, favorite = false)
        val list = listOf(a, b, c)

        val recent = FirmwareStore.sortAndFilterOnDevice(list, LocalSort.Recent, "")
        assertEquals(listOf("2", "3", "1"), recent.map { it.sha })

        val bySize = FirmwareStore.sortAndFilterOnDevice(list, LocalSort.Size, "")
        assertEquals(listOf("2", "1", "3"), bySize.map { it.sha })

        val byName = FirmwareStore.sortAndFilterOnDevice(list, LocalSort.NameAZ, "")
        assertEquals(listOf("2", "3", "1"), byName.map { it.sha })

        val noteMatch = FirmwareStore.sortAndFilterOnDevice(list, LocalSort.Recent, "warm fm")
        assertEquals(listOf("1"), noteMatch.map { it.sha })

        val authorMatch = FirmwareStore.sortAndFilterOnDevice(list, LocalSort.Recent, "hugelton") { id ->
            if (id == "felucca") FirmwareEntry(id = "felucca", name = "Felucca", author = "hugelton") else null
        }
        assertEquals(listOf("1"), authorMatch.map { it.sha })
    }

    @Test
    fun supersededAndUnusedProtectFavoritesAndDocked() {
        val oldFelucca = InstalledPackage("old", "felucca", "Felucca", "1.5", 400_000, addedAt = 1_000, lastPlayedAt = 1_000)
        val newFelucca = InstalledPackage("new", "felucca", "Felucca", "1.6", 500_000, addedAt = 2_000, lastPlayedAt = 2_000)
        val favOld = InstalledPackage("fav", "x0x", "X0X", "1.0", 300_000, addedAt = 500, lastPlayedAt = 500, favorite = true)
        val dockedOld = InstalledPackage("dock", "sloop", "SLOOP", "2.8", 600_000, addedAt = 500, lastPlayedAt = 500)
        val local1 = InstalledPackage("loc1", "local", "Mine", "", 200_000, addedAt = 100, lastPlayedAt = 100)
        val local2 = InstalledPackage("loc2", "local", "Mine2", "", 200_000, addedAt = 200, lastPlayedAt = 200)
        val all = listOf(oldFelucca, newFelucca, favOld, dockedOld, local1, local2)

        val superseded = FirmwareStore.supersededVersionsOf(all)
        assertEquals(listOf("old"), superseded.map { it.sha })

        val unused = FirmwareStore.unusedSinceOf(all, cutoffMs = 1_500, protectedShas = setOf("dock"))
        assertEquals(setOf("old", "loc1", "loc2"), unused.map { it.sha }.toSet())
    }

    @Test
    fun saveStoreListsRecordsAndSizes() {
        val dir = Files.createTempDirectory("harbor-saves-test").toFile()
        try {
            File(dir, "aaa.sectors").writeBytes(ByteArray(SaveStore.RECORD * 3))
            File(dir, "bbb.sectors").writeBytes(ByteArray(SaveStore.RECORD * 1))
            File(dir, "ignore.tmp").writeBytes(ByteArray(123))
            val records = SaveStore.listInDir(dir)
            assertEquals(2, records.size)
            val bySha = records.associateBy { it.sha }
            assertEquals(3, bySha["aaa"]?.sectors)
            assertEquals(1, bySha["bbb"]?.sectors)
            val orphans = records.filter { it.sha !in setOf("aaa") }
            assertEquals(listOf("bbb"), orphans.map { it.sha })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wavHeaderParsingAnd24BitDownscaleWork() {
        fun makeWavHeader(bits: Int, dataBytes: Int): ByteArray {
            val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray())
            bb.putInt(36 + dataBytes)
            bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray())
            bb.putInt(16)
            bb.putShort(1) // PCM
            bb.putShort(2) // stereo
            bb.putInt(44_100)
            bb.putInt(44_100 * 2 * (bits / 8))
            bb.putShort((2 * (bits / 8)).toShort())
            bb.putShort(bits.toShort())
            bb.put("data".toByteArray())
            bb.putInt(dataBytes)
            return bb.array()
        }

        val h16 = makeWavHeader(16, 44_100 * 4 * 2) // 2 seconds
        val i16 = Recordings.parseWavHeader(h16, h16.size, h16.size + 44_100L * 4 * 2)
        assertNotNull(i16)
        assertEquals(16, i16!!.bitsPerSample)
        assertEquals(2000L, i16.durationMs)

        val h24 = makeWavHeader(24, 44_100 * 6 * 3) // 3 seconds
        val i24 = Recordings.parseWavHeader(h24, h24.size, h24.size + 44_100L * 6 * 3)
        assertNotNull(i24)
        assertEquals(24, i24!!.bitsPerSample)
        assertEquals(3000L, i24.durationMs)

        // 24-bit LE samples: [0x11, 0x22, 0x33], [0xAA, 0xBB, 0xCC] -> 16-bit LE [0x22, 0x33, 0xBB, 0xCC]
        val src = byteArrayOf(0x11, 0x22, 0x33, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val dst = ByteArray(4)
        val n = Recordings.downscale24LeTo16Le(src, src.size, dst)
        assertEquals(4, n)
        assertEquals(0x22.toByte(), dst[0])
        assertEquals(0x33.toByte(), dst[1])
        assertEquals(0xBB.toByte(), dst[2])
        assertEquals(0xCC.toByte(), dst[3])

        assertEquals("My Acid Pad", Recordings.sanitizeName("  My/Acid:Pad*?  "))
        assertTrue(Recordings.extensionOf("Take 1.aif", "") == "aiff")
        assertTrue(Recordings.extensionOf("Take 2.m4a", "audio/mp4") == "m4a")
    }
}
