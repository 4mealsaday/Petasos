package com.petasos.app.content

import com.petasos.app.generation.StrictJson
import java.net.URI
import java.security.MessageDigest

/** Bounded, local reviewed-bundle format. This is not an authenticated online distribution channel. */
internal object ContentBundleCodec {
    fun decode(bytes: ByteArray, nowEpochMs: Long): ContentBundle = try {
        val parsed = StrictJson.parse(bytes.copyOf())
        need(parsed is Map<*, *>, "INVALID_BUNDLE")
        @Suppress("UNCHECKED_CAST") val values = parsed as Map<String, Any?>
        val formatVersion = integer(values, "formatVersion")
        val root = when (formatVersion) {
            1 -> obj(parsed, "formatVersion", "sources", "facts", "places", "stories", "versions")
            2 -> obj(parsed, "formatVersion", "sources", "facts", "places", "stories", "versions", "regionBindings")
            else -> fail("UNSUPPORTED_BUNDLE_VERSION")
        }
        val bundle = ContentBundle(
            formatVersion,
            rows(root, "sources").map { raw ->
                val s = obj(raw, "sourceRevisionId", "sourceId", "provider", "sourceUrl", "title", "fetchedAt", "sourceUpdatedAt", "sourceUpdatedRaw", "verifiedAt", "contentHash", "license", "allowedUses", "attribution", "reviewStatus", "normalizedText")
                val l = obj(s["license"], "type", "url", "scope", "reviewBasis")
                val u = obj(s["allowedUses"], "allowGeneration", "allowDisplay", "allowDistribution", "reviewBasis")
                ContentSource(str(s,"sourceRevisionId"),str(s,"sourceId"),str(s,"provider"),str(s,"sourceUrl"),str(s,"title"),
                    long(s,"fetchedAt"),optionalLong(s,"sourceUpdatedAt"),optionalStr(s,"sourceUpdatedRaw"),long(s,"verifiedAt"),str(s,"contentHash"),
                    ContentLicense(str(l,"type"),str(l,"url"),str(l,"scope"),str(l,"reviewBasis")),
                    ContentUses(bool(u,"allowGeneration"),bool(u,"allowDisplay"),bool(u,"allowDistribution"),str(u,"reviewBasis")),
                    str(s,"attribution"),str(s,"reviewStatus"),str(s,"normalizedText"))
            },
            rows(root,"facts").map { raw ->
                val f=obj(raw,"factRevisionId","factId","version","statement","kind","verifiedAt","sourceRevisionIds")
                ContentFact(str(f,"factRevisionId"),str(f,"factId"),integer(f,"version"),str(f,"statement"),str(f,"kind"),long(f,"verifiedAt"),strings(f,"sourceRevisionIds"))
            },
            rows(root,"places").map { raw ->
                val p=obj(raw,"placeId","name","aliases","latitude","longitude","coordinateSystem","coordinateSourceRevisionId","coordinateReviewBasis","externalIds")
                ContentPlace(str(p,"placeId"),str(p,"name"),strings(p,"aliases"),optionalStr(p,"latitude"),optionalStr(p,"longitude"),
                    optionalStr(p,"coordinateSystem"),optionalStr(p,"coordinateSourceRevisionId"),optionalStr(p,"coordinateReviewBasis"),
                    rows(p,"externalIds").map { r -> val e=obj(r,"provider","externalId","verifiedAt"); ContentExternalId(str(e,"provider"),str(e,"externalId"),long(e,"verifiedAt")) })
            },
            rows(root,"stories").map { raw ->
                val s=obj(raw,"storyId","topicId","title","coreQuestion","interestTags","placeId")
                ContentStory(str(s,"storyId"),str(s,"topicId"),str(s,"title"),str(s,"coreQuestion"),strings(s,"interestTags"),optionalStr(s,"placeId"))
            },
            rows(root,"versions").map { raw ->
                val v=obj(raw,"storyVersionId","storyId","version","contentHash","createdAt","verifiedAt","validUntil","estimatedDurationMs","status","paragraphs")
                ContentVersion(str(v,"storyVersionId"),str(v,"storyId"),integer(v,"version"),str(v,"contentHash"),long(v,"createdAt"),long(v,"verifiedAt"),optionalLong(v,"validUntil"),long(v,"estimatedDurationMs"),str(v,"status"),
                    rows(v,"paragraphs").map { r -> val p=obj(r,"paragraphId","ordinal","required","text","audioRef","estimatedDurationMs","factRevisionIds")
                        ContentParagraph(str(p,"paragraphId"),integer(p,"ordinal"),bool(p,"required"),str(p,"text"),optionalStr(p,"audioRef"),long(p,"estimatedDurationMs"),strings(p,"factRevisionIds")) })
            },
            if (formatVersion == 2) rows(root, "regionBindings").map { raw ->
                val r = obj(raw, "storyId", "regionId", "regionRevisionId", "regionCode", "datasetVersion",
                    "regionName", "regionSourceRevisionId", "associationFactRevisionId", "verifiedAt", "reviewBasis")
                ContentRegionBinding(str(r, "storyId"), str(r, "regionId"), str(r, "regionRevisionId"), str(r, "regionCode"),
                    str(r, "datasetVersion"), str(r, "regionName"), str(r, "regionSourceRevisionId"),
                    str(r, "associationFactRevisionId"), long(r, "verifiedAt"), str(r, "reviewBasis"))
            } else emptyList(),
        )
        validate(bundle, nowEpochMs)
        bundle
    } catch (error: ContentException) { throw error }
      catch (_: Exception) { fail("INVALID_BUNDLE") }

    /** Same ordered JSON hash input in the Python compiler and Android validator. */
    fun versionHash(storyId: String, version: Int, paragraphs: List<ContentParagraph>): String = sha256(
        StrictJson.encode(mapOf("storyId" to storyId,"version" to version,"paragraphs" to paragraphs.map(::paragraphMap))))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun licenseJson(source: ContentSource): String = StrictJson.stringify(mapOf("type" to source.license.type,"url" to source.license.url,"scope" to source.license.scope,"reviewBasis" to source.license.reviewBasis))
    fun usesJson(source: ContentSource): String = StrictJson.stringify(mapOf("allowGeneration" to source.allowedUses.allowGeneration,"allowDisplay" to source.allowedUses.allowDisplay,"allowDistribution" to source.allowedUses.allowDistribution,"reviewBasis" to source.allowedUses.reviewBasis))
    fun stringify(value: Any?): String = StrictJson.stringify(value)

    // Fact links are an unordered SQL relation. Canonicalize them for reproducible
    // hashes after a database read; paragraph order itself remains meaningful.
    private fun paragraphMap(p: ContentParagraph): Map<String, Any?> = mapOf("paragraphId" to p.paragraphId,"ordinal" to p.ordinal,"required" to p.required,"text" to p.text,"audioRef" to p.audioRef,"estimatedDurationMs" to p.estimatedDurationMs,"factRevisionIds" to p.factRevisionIds.sorted())

    private fun validate(b: ContentBundle, now: Long) {
        need(b.formatVersion in 1..2, "UNSUPPORTED_BUNDLE_VERSION")
        need(now >= 0 && b.sources.size in 1..64 && b.facts.size in 1..256 && b.places.size <= 64 && b.stories.size in 1..32 && b.versions.size in 1..64, "INVALID_BUNDLE")
        val sources = unique(b.sources) { it.sourceRevisionId }
        val facts = unique(b.facts) { it.factRevisionId }
        val places = unique(b.places) { it.placeId }
        val stories = unique(b.stories) { it.storyId }
        unique(b.versions) { it.storyVersionId }
        need(b.sources.map { it.sourceId to it.contentHash }.distinct().size == b.sources.size, "DUPLICATE_REVISION")
        need(b.facts.map { it.factId to it.version }.distinct().size == b.facts.size, "DUPLICATE_REVISION")
        need(b.versions.map { it.storyId to it.version }.distinct().size == b.versions.size, "DUPLICATE_REVISION")
        b.sources.forEach { s ->
            id(s.sourceId); text(s.provider,128); https(s.sourceUrl); text(s.title,500)
            timestamp(s.fetchedAt); timestamp(s.verifiedAt); s.sourceUpdatedAt?.let(::timestamp)
            s.sourceUpdatedRaw?.let { text(it,128) }
            need(s.reviewStatus == "verified", "SOURCE_NOT_REVIEWED")
            need(s.allowedUses.allowDisplay && s.allowedUses.allowDistribution, "USE_NOT_ALLOWED")
            text(s.allowedUses.reviewBasis,4000); text(s.license.type,128); https(s.license.url)
            need(s.license.scope == "text", "UNSUPPORTED_ASSET_SCOPE")
            text(s.license.reviewBasis,4000); text(s.attribution,1000); text(s.normalizedText,16000)
            need(s.contentHash == sha256(s.normalizedText.toByteArray(Charsets.UTF_8)), "SOURCE_HASH_MISMATCH")
        }
        b.facts.forEach { f ->
            id(f.factId); need(f.version >= 1,"INVALID_REVISION"); text(f.statement,4000); timestamp(f.verifiedAt)
            need(f.kind in setOf("verified_fact","tradition"),"INVALID_FACT_KIND")
            refs(f.sourceRevisionIds,sources.keys)
        }
        val externalIds = mutableSetOf<Pair<String,String>>()
        b.places.forEach { p ->
            text(p.name,500); stringList(p.aliases,32,200)
            p.externalIds.forEach { e -> text(e.provider,128); text(e.externalId,128); timestamp(e.verifiedAt)
                need(externalIds.add(e.provider to e.externalId),"DUPLICATE_EXTERNAL_ID") }
            if (p.latitude == null && p.longitude == null) {
                need(p.coordinateSystem == null && p.coordinateSourceRevisionId == null && p.coordinateReviewBasis == null,"INVALID_COORDINATES")
            } else {
                need(p.coordinateSystem == "WGS84" && p.coordinateSourceRevisionId in sources.keys,"UNREVIEWED_COORDINATES")
                text(p.coordinateReviewBasis ?: fail("UNREVIEWED_COORDINATES"),4000)
                coordinate(p.latitude,-90.0,90.0); coordinate(p.longitude,-180.0,180.0)
            }
        }
        b.stories.forEach { s -> id(s.topicId); text(s.title,500); text(s.coreQuestion,1000); stringList(s.interestTags,20,100)
            need(s.placeId == null || s.placeId in places.keys,"MISSING_REFERENCE") }
        need(b.regionBindings.size <= 32, "INVALID_REGION_BINDING")
        val boundStories = mutableSetOf<String>()
        b.regionBindings.forEach { binding ->
            id(binding.storyId); id(binding.regionId); id(binding.regionRevisionId)
            id(binding.regionSourceRevisionId); id(binding.associationFactRevisionId)
            text(binding.datasetVersion, 128); text(binding.regionName, 500); text(binding.reviewBasis, 4000)
            timestamp(binding.verifiedAt)
            need(Regex("[0-9]{5}").matches(binding.regionCode) && boundStories.add(binding.storyId), "INVALID_REGION_BINDING")
            val fact = facts[binding.associationFactRevisionId]
            need(binding.storyId in stories && fact != null && fact.kind == "verified_fact" &&
                fact.verifiedAt <= binding.verifiedAt && binding.verifiedAt <= now, "INVALID_REGION_BINDING")
            // The pinned geometry source belongs to the existing region catalog. Installation checks it;
            // this transfer document must not fabricate it as a story-content source.
        }
        val paragraphIds = mutableSetOf<String>()
        b.versions.forEach { v ->
            need(v.storyId in stories.keys,"MISSING_REFERENCE"); need(v.version >= 1,"INVALID_REVISION")
            timestamp(v.createdAt); timestamp(v.verifiedAt)
            need(v.status == "published","UNREVIEWED_VERSION")
            need(v.validUntil == null || v.validUntil > now,"EXPIRED_VERSION")
            need(v.estimatedDurationMs in 1..3_600_000,"INVALID_DURATION")
            need(v.paragraphs.size in 1..64 && v.paragraphs.any { it.required },"MISSING_REQUIRED_PARAGRAPH")
            // One fact revision may back several paragraphs (a split story); two revisions of one fact may not mix.
            val stableFacts = mutableMapOf<String, String>()
            v.paragraphs.forEachIndexed { index, p ->
                id(p.paragraphId); need(paragraphIds.add(p.paragraphId),"DUPLICATE_PARAGRAPH")
                need(p.ordinal == index,"INVALID_ORDINAL"); text(p.text,4000)
                need(p.audioRef == null,"UNVERIFIED_MEDIA")
                need(p.estimatedDurationMs in 1..3_600_000,"INVALID_DURATION")
                refs(p.factRevisionIds,facts.keys)
                p.factRevisionIds.forEach { need(stableFacts.getOrPut(facts.getValue(it).factId) { it } == it,"DUPLICATE_STABLE_FACT") }
            }
            need(v.estimatedDurationMs == v.paragraphs.sumOf { it.estimatedDurationMs },"INVALID_DURATION")
            need(v.contentHash == versionHash(v.storyId,v.version,v.paragraphs),"STORY_HASH_MISMATCH")
        }
        need(stories.keys == b.versions.map { it.storyId }.toSet(),"INCOMPLETE_BUNDLE")
    }

    private fun coordinate(value: String?, min: Double, max: Double) {
        need(value != null && value.length <= 64 && Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?").matches(value),"INVALID_COORDINATES")
        val number = value!!.toDoubleOrNull() ?: fail("INVALID_COORDINATES")
        need(number.isFinite() && number in min..max,"INVALID_COORDINATES")
    }
    private fun https(value: String) {
        text(value,2048)
        val uri = try { URI(value) } catch (_: Exception) { fail("INVALID_SOURCE_URL") }
        need(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null,"INVALID_SOURCE_URL")
        need(!Regex("(?i)(servicekey|api[_-]?key|access[_-]?token|authorization)=").containsMatchIn(value),"INVALID_SOURCE_URL")
    }
    private fun <T> unique(values: List<T>, key: (T) -> String): Map<String,T> {
        values.forEach { id(key(it)) }
        val indexed=values.associateBy(key); need(indexed.size==values.size,"DUPLICATE_ID"); return indexed
    }
    private fun refs(values: List<String>, keys: Set<String>) { need(values.isNotEmpty() && values.distinct().size==values.size && keys.containsAll(values),"MISSING_REFERENCE") }
    private fun stringList(values: List<String>, size: Int, length: Int) { need(values.size<=size && values.distinct().size==values.size,"INVALID_BUNDLE"); values.forEach { text(it,length) } }
    private fun id(value: String) { need(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(value),"INVALID_ID") }
    private fun timestamp(value: Long) { need(value>=0,"INVALID_TIMESTAMP") }
    private fun text(value: String, limit: Int) { need(value.isNotBlank() && value.length<=limit && value.none { it.code<32 && it !in "\n\r\t" },"INVALID_TEXT") }
    private fun obj(value: Any?, vararg keys: String): Map<String,Any?> {
        need(value is Map<*,*> && value.keys == keys.toSet(),"INVALID_BUNDLE")
        @Suppress("UNCHECKED_CAST") return value as Map<String,Any?>
    }
    private fun str(value: Map<String,Any?>, key: String) = value[key] as? String ?: fail("INVALID_BUNDLE")
    private fun long(value: Map<String,Any?>, key: String) = value[key] as? Long ?: fail("INVALID_BUNDLE")
    private fun integer(value: Map<String,Any?>, key: String): Int { val n=long(value,key); need(n in Int.MIN_VALUE..Int.MAX_VALUE,"INVALID_BUNDLE"); return n.toInt() }
    private fun bool(value: Map<String,Any?>, key: String) = value[key] as? Boolean ?: fail("INVALID_BUNDLE")
    private fun optionalStr(value: Map<String,Any?>, key: String): String? = if(value[key]==null) null else str(value,key)
    private fun optionalLong(value: Map<String,Any?>, key: String): Long? = if(value[key]==null) null else long(value,key)
    private fun rows(value: Map<String,Any?>, key: String) = value[key] as? List<*> ?: fail("INVALID_BUNDLE")
    private fun strings(value: Map<String,Any?>, key: String) = rows(value,key).map { it as? String ?: fail("INVALID_BUNDLE") }
    private fun need(condition: Boolean, code: String) { if(!condition) fail(code) }
    private fun fail(code: String): Nothing = throw ContentException(code)
}
