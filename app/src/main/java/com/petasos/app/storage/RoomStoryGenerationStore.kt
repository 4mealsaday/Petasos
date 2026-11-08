package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.content.ContentParagraph
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.StoryGenerationContext
import com.petasos.app.generation.StrictJson
import com.petasos.app.generation.ValidatedGenerationStory
import com.petasos.app.playback.OperationToken
import com.petasos.app.playback.PreparedStory
import java.util.concurrent.Callable

/** Opaque read snapshot. It confers no permission after its currentness guard has become false. */
class PreparedStoryGeneration internal constructor(
    val context: StoryGenerationContext,
    internal val owner: Any,
    internal val request: OperationToken,
    internal val seedVersionId: String,
    internal val requestedExcludedFactIds: List<String>,
    internal val fingerprint: String,
    internal val createdAt: Long,
    internal val runtimeCurrent: () -> Boolean,
) { override fun toString() = "PreparedStoryGeneration([redacted])" }

/**
 * Worker-thread, read-only preparation followed by atomic TX-05 append of public generated content.
 * Only explicit vehicle keywords are read. This store never reads or updates conversation payloads.
 * No network, audio, automatic selection, collection credit or location consent belongs here.
 */
class RoomStoryGenerationStore internal constructor(
    private val database: StorageDatabase,
    private val wallTime: () -> Long,
    private val ownsDatabase: Boolean = false,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), System::currentTimeMillis, true,
    )

    private val owner = Any()
    private val installer = RoomContentInstaller(database, wallTime, ownsDatabase = false)
    private val sql get() = database.openHelper.writableDatabase

    fun prepare(
        request: OperationToken,
        seedVersionId: String,
        excludedFactIds: List<String>,
        routeRevision: Long? = null,
        isRuntimeCurrent: () -> Boolean,
    ): PreparedStoryGeneration? {
        if (!current(isRuntimeCurrent) || !validId(request.id) || !validId(request.sessionId) ||
            request.cancelEpoch < 0 || routeRevision?.let { it < 0 } == true || !validId(seedVersionId) ||
            excludedFactIds.size > 50 || excludedFactIds.distinct().size != excludedFactIds.size ||
            excludedFactIds.any { !validId(it) }) return null
        val excluded = excludedFactIds.sorted()
        val initial = transaction { snapshot(request, seedVersionId, excluded, routeRevision) } ?: return null
        val createdAt = wallTime()
        if (createdAt < 0 || !current(isRuntimeCurrent)) return null
        val context = StoryGenerationContext(initial.storyId, initial.topicId, initial.regionId,
            initial.sources.keys.sorted(), initial.excludedFactIds, initial.keywords, initial.factSources,
            initial.factKinds, initial.factStableIds, routeRevision,
            isCurrent = {
                try {
                    current(isRuntimeCurrent) && transaction {
                        snapshot(request, seedVersionId, excluded, routeRevision)?.fingerprint == initial.fingerprint
                    } == true && current(isRuntimeCurrent)
                } catch (_: StorageUnavailable) { false }
            })
        return PreparedStoryGeneration(context, owner, request.copy(), seedVersionId,
            excluded, initial.fingerprint, createdAt, isRuntimeCurrent)
    }

    /** Returns null without writes for stale, revoked, malformed or already installed work. */
    fun install(work: PreparedStoryGeneration, result: ValidatedGenerationStory): PreparedStory? {
        if (work.owner !== owner || !current(work.runtimeCurrent)) return null
        return transaction {
            val initial = snapshot(work.request, work.seedVersionId, work.requestedExcludedFactIds, work.context.routeRevision)
                ?: reject()
            if (initial.fingerprint != work.fingerprint || !current(work.runtimeCurrent)) reject()
            val now = wallTime()
            if (now < work.createdAt) reject()
            val version = version(initial, result, now)
            if (exists("SELECT 1 FROM story_version WHERE story_version_id=?", version.storyVersionId) ||
                exists("SELECT 1 FROM generation_request WHERE request_id=?", work.request.id) ||
                version.paragraphs.any { exists("SELECT 1 FROM paragraph WHERE paragraph_id=?", it.paragraphId) }) reject()
            if (!current(work.runtimeCurrent)) reject()
            installer.installGeneratedVersion(work.seedVersionId, version)
            // Provenance is session/request scoped in v0.8; there is no version -> request FK.
            // usage_json is exclusively usage metadata, never an implicit content association.
            val usage = StrictJson.stringify(mapOf("inputTokens" to result.inputTokens, "outputTokens" to result.outputTokens))
            sql.execSQL("INSERT INTO generation_request(request_id,session_id,task,input_hash,schema_version,cancel_epoch,route_revision,created_at,finished_at,status,attempt_count,model_id,provider,prompt_version,usage_json) VALUES (?,?,'story',?,'0.8',?,?,?,?,'succeeded',1,?,?,?,?)",
                arrayOf<Any?>(work.request.id, work.request.sessionId, result.inputHash, work.request.cancelEpoch,
                    work.context.routeRevision, work.createdAt, now, result.modelId, result.provider, result.promptVersion, usage))
            // A concurrent Stop may revoke the in-memory operation while SQLite is committing.
            if (!current(work.runtimeCurrent) || !sessionCurrent(work.request, work.context.routeRevision)) reject()
            installer.publishedStory(version.storyVersionId)
        }
    }

    private class Snapshot(
        val storyId: String, val topicId: String, val regionId: String?, val nextVersion: Int,
        val validUntil: Long?, val keywords: List<String>, val sources: Map<String, Map<String, Any?>>,
        val factSources: Map<String, Set<String>>, val factKinds: Map<String, String>,
        val factStableIds: Map<String, String>, val excludedFactIds: List<String>, val fingerprint: String,
    ) { override fun toString() = "StoryGenerationSnapshot([redacted])" }

    private fun snapshot(request: OperationToken, seedVersionId: String, excluded: List<String>, routeRevision: Long?): Snapshot? {
        if (!sessionCurrent(request, routeRevision) || exists("SELECT 1 FROM generation_request WHERE request_id=?", request.id)) return null
        val prepared = installer.publishedStory(seedVersionId)
        val version = row("SELECT * FROM story_version WHERE story_version_id=?", seedVersionId) ?: reject()
        val story = row("SELECT * FROM story WHERE story_id=?", prepared.storyId) ?: reject()
        val topic = story["topic_id"] as? String ?: reject()
        val region = story["region_id"] as? String
        if (!validId(prepared.storyId) || !validId(topic) || region?.let { !validId(it) } == true) reject()
        val regionState = region?.let { row("SELECT * FROM region WHERE region_id=?", it) ?: reject() }
        val regionBinding = region?.let {
            RoomRegionalContentBindings(database, wallTime).currentKey(prepared.storyId, it, forGeneration = true) ?: reject()
        }
        val maxVersion = query("SELECT max(version) FROM story_version WHERE story_id=?", prepared.storyId).use {
            if (!it.moveToFirst() || it.isNull(0)) reject()
            it.getLong(0)
        }
        if (maxVersion !in 1 until Int.MAX_VALUE.toLong()) reject()
        val preference = row("SELECT keywords_json,version FROM preference WHERE scope_id='vehicle'")
        val keywords = if (preference == null) emptyList() else strings(preference["keywords_json"] as? String ?: reject())
        if (keywords.size > 20 || keywords.distinct().size != keywords.size || keywords.any { it.isBlank() || it.length > 256 } ||
            preference != null && (preference["version"] as? Long ?: 0) < 1) reject()
        val sources = linkedMapOf<String, Map<String, Any?>>()
        val facts = linkedMapOf<String, Map<String, Any?>>()
        val factSources = linkedMapOf<String, Set<String>>()
        val kinds = linkedMapOf<String, String>()
        val stableIds = linkedMapOf<String, String>()
        val allStableIds = mutableSetOf<String>()
        query("SELECT DISTINCT f.* FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id JOIN fact_revision f ON f.fact_revision_id=pf.fact_revision_id WHERE p.story_version_id=? ORDER BY f.fact_revision_id", seedVersionId).use { cursor ->
            while (cursor.moveToNext()) {
                val fact = values(cursor)
                val id = fact["fact_revision_id"] as? String ?: reject()
                val stable = fact["fact_id"] as? String ?: reject()
                val kind = when (fact["kind"]) { "verified_fact" -> "fact"; "tradition" -> "tradition"; else -> reject() }
                if (!validId(id) || !validId(stable) || !allStableIds.add(stable) ||
                    (fact["version"] as? Long ?: 0) < 1 || (fact["verified_at"] as? Long ?: -1) < 0 ||
                    (fact["statement"] as? String).isNullOrBlank()) reject()
                if (stable in excluded) continue
                val links = linkedSetOf<String>()
                query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", id).use {
                    while (it.moveToNext()) links += it.getString(0)
                }
                if (links.isEmpty() || links.any { !validId(it) }) reject()
                links.forEach { sourceId -> sources[sourceId] = source(sourceId) }
                facts[id] = fact + ("sourceRevisionIds" to links.toList())
                factSources[id] = links.toSet(); kinds[id] = kind; stableIds[id] = stable
            }
        }
        if (facts.isEmpty() || sources.size !in 1..50) reject()
        // The wire request identifies sources, not the seed's approved fact revisions. The server
        // can therefore see older/local facts omitted by a valid newer story. Exclude those known
        // stable facts without granting any additional fact permission to this seed.
        val pool = localFactPool(sources.keys)
        val approvedStable = stableIds.values.toSet()
        val effectiveExcluded = (excluded + pool.values.map { it["fact_id"] as String }
            .filter { it !in approvedStable }).toSortedSet().toList()
        if (effectiveExcluded.size > 50) reject()
        val fingerprint = ContentBundleCodec.sha256(StrictJson.encode(mapOf(
            "sessionId" to request.sessionId, "cancelEpoch" to request.cancelEpoch, "routeRevision" to routeRevision,
            "seed" to version, "story" to story, "region" to regionState, "regionBinding" to regionBinding, "maxVersion" to maxVersion,
            "preference" to preference, "requestedExcludedFactIds" to excluded, "excludedFactIds" to effectiveExcluded,
            "facts" to facts, "sources" to sources, "sourceFactPool" to pool,
        )))
        return Snapshot(prepared.storyId, topic, region, (maxVersion + 1).toInt(), version["valid_until"] as? Long,
            keywords, sources, factSources, kinds, stableIds, effectiveExcluded, fingerprint)
    }

    /** Only fully source-supported local revisions participate, matching the server's resolve rule. */
    private fun localFactPool(sourceIds: Set<String>): Map<String, Map<String, Any?>> {
        val pool = linkedMapOf<String, Map<String, Any?>>()
        val placeholders = sourceIds.joinToString(",") { "?" }
        query("SELECT DISTINCT f.* FROM fact_revision f JOIN fact_source fs ON fs.fact_revision_id=f.fact_revision_id WHERE fs.source_revision_id IN ($placeholders) ORDER BY f.fact_revision_id",
            *sourceIds.sorted().toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val state = values(cursor)
                val revisionId = state["fact_revision_id"] as? String ?: reject()
                val stableId = state["fact_id"] as? String ?: reject()
                if (!validId(revisionId) || !validId(stableId)) reject()
                val links = linkedSetOf<String>()
                query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", revisionId).use {
                    while (it.moveToNext()) links += it.getString(0)
                }
                if (links.isNotEmpty() && sourceIds.containsAll(links)) {
                    pool[revisionId] = state + ("sourceRevisionIds" to links.toList())
                }
            }
        }
        return pool
    }

    private fun version(snapshot: Snapshot, result: ValidatedGenerationStory, now: Long): ContentVersion {
        if (result.storyId != snapshot.storyId || !validId(result.storyVersionId) || result.paragraphs.size !in 1..12 ||
            result.estimatedDurationMs !in result.paragraphs.size.toLong()..3_600_000L ||
            !Regex("[a-f0-9]{64}").matches(result.inputHash) ||
            listOf(result.modelId, result.provider, result.promptVersion).any { it.isBlank() || it.length > 256 } ||
            result.inputTokens?.let { it < 0 } == true || result.outputTokens?.let { it < 0 } == true) reject()
        val ids = mutableSetOf<String>()
        val stable = mutableSetOf<String>()
        // The response supplies only a total estimate. Equal shares preserve that estimate exactly;
        // these are not measured TTS durations or playback completion evidence.
        val eachDuration = result.estimatedDurationMs / result.paragraphs.size
        val remainder = result.estimatedDurationMs % result.paragraphs.size
        val paragraphs = result.paragraphs.mapIndexed { index, p ->
            if (!validId(p.key) || !ids.add(p.key) || p.kind !in setOf("fact", "tradition") ||
                p.text.value.isBlank() || p.text.value.length > 240 || p.factRevisionIds.isEmpty() ||
                p.factRevisionIds.size > 50 || p.factRevisionIds.distinct().size != p.factRevisionIds.size ||
                p.sourceRevisionIds.isEmpty() || p.sourceRevisionIds.size > 50 ||
                p.sourceRevisionIds.distinct().size != p.sourceRevisionIds.size) reject()
            val requiredSources = linkedSetOf<String>()
            p.factRevisionIds.forEach { id ->
                if (snapshot.factKinds[id] != p.kind || !stable.add(snapshot.factStableIds[id] ?: reject())) reject()
                requiredSources += snapshot.factSources[id] ?: reject()
            }
            if (p.sourceRevisionIds.toSet() != requiredSources) reject()
            ContentParagraph(p.key, index, true, p.text.value, null,
                eachDuration + if (index.toLong() < remainder) 1 else 0, p.factRevisionIds.sorted())
        }
        return ContentVersion(result.storyVersionId, snapshot.storyId, snapshot.nextVersion,
            ContentBundleCodec.versionHash(snapshot.storyId, snapshot.nextVersion, paragraphs), now, now,
            snapshot.validUntil, result.estimatedDurationMs, "published", paragraphs)
    }

    private fun source(id: String): Map<String, Any?> {
        val source = row("SELECT * FROM source_evidence WHERE source_revision_id=?", id) ?: reject()
        val uses = try { StrictJson.parse((source["allowed_uses_json"] as? String ?: reject()).toByteArray()) as? Map<*, *> }
        catch (_: Exception) { reject() }
        if (source["review_status"] != "verified" || (source["verified_at"] as? Long ?: -1) < 0 ||
            uses?.get("allowGeneration") != true || uses?.get("allowDisplay") != true || uses?.get("allowDistribution") != true ||
            !Regex("[a-f0-9]{64}").matches(source["content_hash"] as? String ?: "")) reject()
        return source
    }

    private fun sessionCurrent(request: OperationToken, routeRevision: Long?): Boolean = query(
        "SELECT s.cancel_epoch,s.route_revision FROM session s JOIN trip t ON t.trip_id=s.trip_id WHERE s.session_id=? AND s.status='active' AND t.status='active'", request.sessionId,
    ).use { it.moveToFirst() && it.getLong(0) == request.cancelEpoch && (routeRevision == null || it.getLong(1) == routeRevision) }

    private fun strings(json: String): List<String> = try {
        val parsed = StrictJson.parse(json.toByteArray()) as? List<*> ?: reject()
        parsed.map { it as? String ?: reject() }
    } catch (_: Exception) { reject() }

    private fun row(statement: String, vararg args: Any?): Map<String, Any?>? = query(statement, *args).use {
        if (it.moveToFirst()) values(it) else null
    }

    private fun values(cursor: Cursor): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        (0 until cursor.columnCount).forEach { index ->
            this[cursor.getColumnName(index)] = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                else -> cursor.getString(index)
            }
        }
    }

    private fun exists(statement: String, vararg args: Any?) = query(statement, *args).use { it.moveToFirst() }
    private fun query(statement: String, vararg args: Any?) = sql.query(SimpleSQLiteQuery(statement, args))
    private fun validId(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(value)
    private fun current(guard: () -> Boolean) = try { guard() } catch (_: Exception) { false }
    private class Rejected : RuntimeException("story_generation_rejected")
    private fun reject(): Nothing = throw Rejected()

    private fun <T> transaction(action: () -> T): T? = try { database.runInTransaction(Callable { action() }) }
    catch (_: Rejected) { null }
    catch (failure: ContentException) {
        if (failure.code == "CONTENT_STORAGE_FAILURE") throw StorageUnavailable("story_generation_storage_failure")
        null
    } catch (_: Exception) { throw StorageUnavailable("story_generation_storage_failure") }

    override fun close() { if (ownsDatabase) database.close() }
}
