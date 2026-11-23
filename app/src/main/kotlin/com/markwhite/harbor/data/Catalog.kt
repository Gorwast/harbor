package com.markwhite.harbor.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One firmware: from fm1-emu's catalog (web/firmwares.json), brought up to date from the FM-1
 * Firmware Hub's catalog when the hub carries it (see [Catalog.merge]).
 */
@Serializable
data class FirmwareEntry(
    val id: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val license: String = "",
    val about: String = "",
    val repo: String? = null,
    /** where the package downloads from */
    val `package`: String? = null,
    val installer: String? = null,
    val hub: String? = null,
    /** bring your own: Harbor may not download it (M-VAVE's stock firmware) */
    val byo: Boolean = false,
    /** where the user can download a bring-your-own package themselves */
    val download: String? = null,
    /** the package lives inside its installer page (April OS): not available in Harbor yet */
    val embedded: String? = null,
    /** the package's sha256 (hex), when the hub publishes one: downloads are checked against it */
    val sha256: String? = null,
    /** fm1-emu's own URL for the package, tried if the hub's fails */
    val listedPackage: String? = null,
    /** on the hub only: fm1-emu hasn't checked it runs in the emulator */
    val hubOnly: Boolean = false,
    /** its author calls it a beta */
    val beta: Boolean = false,
    /** a screenshot (the Hub's), any size; it may be missing or broken, so it is only ever a nicety */
    val image: String? = null,
    /** category tags from the Hub (`synth`, `drums`, `groove`, `chords`, `dev`, `other`, `sampler`) */
    val kinds: List<String> = emptyList(),
    val installs: Int = 0,
    val stars: Int = 0,
    val added: String = "",
    val updated: String = "",
    val chip: String? = null,
    val release: String? = null,
) {
    val title: String get() = if (version.isBlank()) name else "$name $version"
    val playable: Boolean get() = `package` != null || byo
}

enum class FirmwareSort(val label: String) {
    Default("Default"),
    MostInstalled("Most installed"),
    TopRated("Top rated"),
    WhatsNew("What's new"),
    RecentlyUpdated("Recently updated"),
    NameAZ("A–Z"),
}

enum class FirmwareCategory(val label: String) {
    All("All"),
    Synth("Synth"),
    Drums("Drums"),
    Sampler("Sampler"),
    Groovebox("Groovebox"),
    Chords("Chords"),
    Dev("Dev"),
    Other("Other"),
    ;

    fun matches(e: FirmwareEntry): Boolean = when (this) {
        All -> true
        Synth -> "synth" in e.kinds
        Drums -> "drums" in e.kinds
        Sampler -> "sampler" in e.kinds || e.about.contains("sampler", ignoreCase = true) || e.about.contains("sampling", ignoreCase = true)
        Groovebox -> "groove" in e.kinds || "groovebox" in e.kinds
        Chords -> "chords" in e.kinds
        Dev -> "dev" in e.kinds
        Other -> "other" in e.kinds || e.kinds.isEmpty()
    }
}

@Serializable
data class CatalogFile(val default: String = "felucca", val firmwares: List<FirmwareEntry>)

/** The FM-1 Firmware Hub's catalog.json: the fields Harbor uses. */
@Serializable
data class HubFile(val updated: String? = null, val firmwares: List<HubEntry> = emptyList())

@Serializable
data class HubEntry(
    val id: String,
    val name: String = "",
    val author: String = "",
    val what: String = "",
    val version: String = "",
    /** path under the hub's root */
    val pkg: String? = null,
    val sha256: String? = null,
    /** needs another firmware's loader (M-VAVE's): not something Harbor can boot alone */
    val loader: String? = null,
    /** the board it is built for, "FM-1_..." for the FM-1 */
    val product: String? = null,
    val beta: Boolean = false,
    val repo: String? = null,
    val source: String? = null,
    val kind: JsonElement? = null,
    val installs: Int = 0,
    val stars: Int = 0,
    val added: String = "",
    val date: String = "",
    val published: String = "",
    val chip: String? = null,
    val release: String? = null,
) {
    fun parsedKinds(): List<String> = when (kind) {
        is JsonPrimitive -> listOfNotNull(kind.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() })
        is JsonArray -> kind.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { s -> s.isNotBlank() } }
        else -> emptyList()
    }
}

/** Per-firmware notes the catalog doesn't carry yet (ui-spec GAP-5), from fm1-emu's guide. */
object FirmwareNotes {
    private val felucca = setOf("felucca", "felucca-salt", "felucca8", "ghoulbox", "melodee", "rainbow", "noizdemons", "waveloop", "hortator")
    private val noPlainNoteOn = setOf("choralroot", "hortator", "sloop-alg", "x0x")

    fun midiHint(id: String): String? = when {
        id == "mvave" -> "Listens on channel 2 (seen in the emulator; unverified on hardware)."
        id in noPlainNoteOn -> "Makes no sound from a plain note-on."
        id in felucca -> "Parts play on channels 1–4."
        else -> null
    }

    /** The channel a firmware wants, if it wants one (0-based). */
    fun preferredChannel(id: String): Int? = if (id == "mvave") 1 else null

    /** MIDI-controller firmwares make no sound of their own (ui-spec M12). */
    fun makesNoSound(id: String): Boolean = id == "ctl"
}

/**
 * The firmware list: fm1-emu's catalog (which firmwares run in the emulator, and what they are)
 * merged with the FM-1 Firmware Hub's (where each one's current package is, with its checksum,
 * and the firmwares fm1-emu doesn't list yet). Both are bundled, cached and refreshed daily.
 */
class Catalog(private val context: Context) {
    private val cache = File(context.filesDir, "catalog.json")
    private val hubCache = File(context.filesDir, "hub-catalog.json")
    private var listed: CatalogFile = load(cache, "firmwares.json", ::parse)
    private var hub: HubFile = load(hubCache, "hub-catalog.json", ::parseHub)
    private val _entries = MutableStateFlow(shown(merge(listed, hub)))
    val entries: StateFlow<CatalogFile> = _entries

    /** When the shown list was fetched (0: the snapshot bundled with the app). */
    var refreshedAt: Long = if (cache.exists()) cache.lastModified() else 0L
        private set

    private fun <T> load(file: File, asset: String, parser: (String) -> T): T {
        val bundled = { parser(context.assets.open(asset).bufferedReader().use { it.readText() }) }
        val text = runCatching { file.readText() }.getOrNull() ?: return bundled()
        return runCatching { parser(text) }.getOrElse { bundled() }
    }

    fun byId(id: String): FirmwareEntry? = _entries.value.firmwares.firstOrNull { it.id == id }

    /** Refresh when the cached lists are a day old (at launch). */
    suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - refreshedAt > 24 * 3600_000L) refresh()
    }

    /** Fetch both lists; each keeps its current version if its fetch fails. */
    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        val a = runCatching {
            val text = fetch(CATALOG_URL)
            val parsed = parse(text)
            require(parsed.firmwares.isNotEmpty()) { "empty catalog" }
            store(cache, text)
            listed = parsed
        }
        // the hub's rule: read catalog.json at most once a day
        val hubStale = !hubCache.exists() || System.currentTimeMillis() - hubCache.lastModified() >= 24 * 3600_000L
        val b = if (hubStale) runCatching {
            val text = fetch(HUB_CATALOG_URL + "?" + java.time.LocalDate.now(java.time.ZoneOffset.UTC))
            val parsed = parseHub(text)
            require(parsed.firmwares.isNotEmpty()) { "empty hub catalog" }
            store(hubCache, text)
            hub = parsed
        } else Result.success(Unit)
        if (a.isSuccess) refreshedAt = System.currentTimeMillis()
        _entries.value = shown(merge(listed, hub))
        a.onFailure { com.markwhite.harbor.engine.EventLog.w("catalog refresh failed: ${it.message}") }
        b.onFailure { com.markwhite.harbor.engine.EventLog.w("hub catalog refresh failed: ${it.message}") }
        if (a.isFailure && b.isFailure) Result.failure(a.exceptionOrNull()!!) else Result.success(Unit)
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun store(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    companion object {
        const val CATALOG_URL = "https://joakimeriksson.github.io/fm1-emu/firmwares.json"
        const val HUB = "https://thefirmwarehub.com/"
        const val HUB_CATALOG_URL = HUB + "catalog.json"
        val USER_AGENT: String = "Harbor/${com.markwhite.harbor.BuildConfig.VERSION_NAME} (Android; +https://github.com/Gorwast/harbor)"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Firmwares Play builds don't list: Harbor is rated Everyone, and a shooter in the catalogue
         * would rate it Teen. Matched on the name too, since the hub may know one by another id.
         */
        private val PLAY_HIDDEN = Regex("doom", RegexOption.IGNORE_CASE)

        fun shown(c: CatalogFile, play: Boolean = com.markwhite.harbor.BuildConfig.PLAY_CATALOG): CatalogFile =
            if (!play) c else c.copy(firmwares = c.firmwares.filterNot { PLAY_HIDDEN.containsMatchIn(it.id) || PLAY_HIDDEN.containsMatchIn(it.name) })

        /**
         * Where the Hub keeps a firmware's picture: its own page draws them from here, though its
         * catalog.json doesn't list them. Most are the firmware's screen; some are a banner or a photo.
         */
        fun hubImage(hubId: String): String = HUB + "img/" + hubId + ".png"

        /** fm1-emu's ids for firmwares the hub knows by another name. */
        private val HUB_ALIASES = mapOf("quest" to "fm1quest")

        /** fm1-emu's web/firmwares.json; fields Harbor doesn't know are ignored. */
        fun parse(text: String): CatalogFile = json.decodeFromString(CatalogFile.serializer(), text)

        fun parseHub(text: String): HubFile = json.decodeFromString(HubFile.serializer(), text)

        /**
         * fm1-emu's entries, each taking the hub's current package (version, URL, checksum) when
         * the hub has one it can boot; then the hub's other FM-1 firmwares, marked as unchecked.
         */
        fun merge(listed: CatalogFile, hub: HubFile): CatalogFile {
            val byId = hub.firmwares.associateBy { it.id }
            fun usable(h: HubEntry) = h.pkg != null && h.loader == null && h.product?.startsWith("FM-1") != false
            val linked = HashSet<String>()
            val entries = listed.firmwares.map { e ->
                val h = (e.hub ?: HUB_ALIASES[e.id] ?: e.id).let(byId::get)
                if (h == null || e.byo || !usable(h)) return@map e
                linked += h.id
                e.copy(
                    version = h.version.ifBlank { e.version },
                    `package` = HUB + h.pkg,
                    sha256 = h.sha256,
                    listedPackage = e.`package`,
                    hub = h.id,
                    beta = h.beta,
                    image = hubImage(h.id),
                    kinds = h.parsedKinds(),
                    installs = h.installs,
                    stars = h.stars,
                    added = h.added,
                    updated = h.published.ifBlank { h.date },
                    chip = h.chip,
                    release = h.release,
                )
            }
            val listedIds = listed.firmwares.map { it.id }.toSet()
            val extra = hub.firmwares.filter { usable(it) && it.id !in linked && it.id !in listedIds }.map { h ->
                FirmwareEntry(
                    id = h.id, name = h.name.ifBlank { h.id }, version = h.version, author = h.author, about = h.what,
                    repo = h.source ?: h.repo?.let { "https://github.com/$it" }, `package` = HUB + h.pkg, hub = h.id,
                    sha256 = h.sha256, hubOnly = true, beta = h.beta, image = hubImage(h.id),
                    kinds = h.parsedKinds(), installs = h.installs, stars = h.stars,
                    added = h.added, updated = h.published.ifBlank { h.date }, chip = h.chip, release = h.release,
                )
            }.sortedBy { it.name.lowercase() }
            return listed.copy(firmwares = entries + extra)
        }

        fun filterAndSort(
            entries: List<FirmwareEntry>,
            query: String,
            category: FirmwareCategory,
            sort: FirmwareSort,
        ): List<FirmwareEntry> {
            val filtered = entries.filter { e ->
                val matchesQuery = query.isBlank() ||
                    listOf(e.name, e.author, e.about).any { it.contains(query, ignoreCase = true) }
                matchesQuery && category.matches(e)
            }
            return when (sort) {
                FirmwareSort.Default -> filtered
                FirmwareSort.MostInstalled -> filtered.sortedWith(compareByDescending<FirmwareEntry> { it.installs }.thenBy { it.name.lowercase() })
                FirmwareSort.TopRated -> filtered.sortedWith(compareByDescending<FirmwareEntry> { it.stars }.thenByDescending { it.installs }.thenBy { it.name.lowercase() })
                FirmwareSort.WhatsNew -> filtered.sortedWith(compareByDescending<FirmwareEntry> { it.added }.thenByDescending { it.updated }.thenBy { it.name.lowercase() })
                FirmwareSort.RecentlyUpdated -> filtered.sortedWith(compareByDescending<FirmwareEntry> { it.updated }.thenBy { it.name.lowercase() })
                FirmwareSort.NameAZ -> filtered.sortedBy { it.name.lowercase() }
            }
        }
    }
}
