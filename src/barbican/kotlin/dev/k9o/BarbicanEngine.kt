package dev.k9o

import uniffi.k9o_keep_core_ffi_uniffi.KeepConfig
import uniffi.k9o_keep_core_ffi_uniffi.KeepCore
import uniffi.k9o_keep_core_ffi_uniffi.KeepException
import uniffi.k9o_keep_core_ffi_uniffi.KeepRule
import uniffi.k9o_keep_core_ffi_uniffi.KeepRulesetBlob
import uniffi.k9o_keep_core_ffi_uniffi.KeepSignedRulesetBlob
import uniffi.k9o_keep_core_ffi_uniffi.KeepStreamFilter

/**
 * Adapter between the uniffi-generated Kotlin binding and the Java facade ([Barbican]).
 *
 * Exists for exactly one reason: the generated binding uses Kotlin unsigned types
 * (ULong/UInt/UByte), whose JVM signatures are name-mangled and cannot be called from Java.
 * This file only converts unsigned <-> signed integers and hands typed values across. It holds
 * NO crypto, NO key and NO trust decision: every verification, verdict and redaction is the native
 * engine's. A refused load surfaces as [Refusal] carrying the engine's closed reason token verbatim.
 */
class Refusal(val reason: String) : RuntimeException("reason=$reason")

class NRule(val id: String, val pattern: String, val severity: String)

class NMatched(val ruleId: String, val spanStart: Long, val spanEnd: Long, val severity: String, val matchedForm: String)

class NVerdict(
    val decision: String,
    val reason: String,
    val matched: List<NMatched>,
    val schemaValid: Boolean,
    val waypointClassHint: String?,
    val failMode: String?,
    val rulesetVersion: Long,
    val coreVersion: String,
)

class NManifestInfo(
    val rulesetVersion: Long,
    val signingKeyKid: String,
    val artifactHash: String?,
    val artifactUrl: String?,
    val validUntil: Long?,
)

class NLeakReport(val emails: Long, val secrets: Long, val markers: Long, val total: Long)

class NFilter internal constructor(private val f: KeepStreamFilter) : AutoCloseable {
    fun push(chunk: String): String = f.push(chunk)
    fun finish(): String = f.finish()
    fun report(): NLeakReport = f.report().let {
        NLeakReport(it.emails.toLong(), it.secrets.toLong(), it.markers.toLong(), it.total.toLong())
    }
    override fun close() = f.close()
}

class BarbicanEngine(failOpen: Boolean, leet: Boolean, decodeDepth: Int) : AutoCloseable {
    private val core = KeepCore(KeepConfig(failOpen, leet, decodeDepth.toUByte()))

    companion object {
        @JvmStatic
        fun testRoot(): Boolean = uniffi.k9o_keep_core_ffi_uniffi.usesTestTrustRoot()
    }

    private inline fun <T> refusals(f: () -> T): T =
        try { f() } catch (e: KeepException.RulesetInvalid) { throw Refusal(e.reason) }

    fun loadRulesetUnsigned(version: Long, rules: List<NRule>): Long = refusals {
        core.loadRulesetUnsigned(KeepRulesetBlob(version.toULong(), rules.map { KeepRule(it.id, it.pattern, it.severity) })).toLong()
    }

    fun loadKeyManifest(jws: String, nowSecs: Long): Long = refusals { core.loadKeyManifest(jws, nowSecs.toULong()).toLong() }

    fun verifyManifest(jws: String): NManifestInfo = refusals {
        core.verifyManifest(jws).let {
            NManifestInfo(it.rulesetVersion.toLong(), it.signingKeyKid, it.artifactHash, it.artifactUrl, it.validUntil?.toLong())
        }
    }

    private fun signed(version: Long, rules: List<NRule>, kid: String, signature: ByteArray, createdAt: Long) =
        KeepSignedRulesetBlob(version.toULong(), rules.map { KeepRule(it.id, it.pattern, it.severity) }, kid, signature, createdAt)

    fun loadRulesetSigned(version: Long, rules: List<NRule>, kid: String, signature: ByteArray, createdAt: Long): Long =
        refusals { core.loadRulesetSigned(signed(version, rules, kid, signature, createdAt)).toLong() }

    fun rulesetDigest(version: Long, rules: List<NRule>, kid: String, signature: ByteArray, createdAt: Long): String =
        refusals { core.rulesetDigest(signed(version, rules, kid, signature, createdAt)) }

    fun keyManifestSequence(): Long = core.keyManifestSequence().toLong()
    fun signedFloor(): Long = core.signedFloor().toLong()
    fun activeRulesetIsSigned(): Boolean = core.activeRulesetIsSigned()
    fun trustedKids(): List<String> = core.trustedKids()
    fun deprecatedKids(): List<String> = core.deprecatedKids()
    fun coreVersion(): String = core.coreVersion()
    fun classify(text: String): String = core.classifyLocal(text)
    fun outputFilter(): NFilter = NFilter(core.outputFilter())

    fun inspect(text: String, waypointClass: String?): NVerdict = core.inspectInput(text, waypointClass).let { v ->
        NVerdict(
            v.decision, v.reason,
            v.matched.map { NMatched(it.ruleId, it.spanStart.toLong(), it.spanEnd.toLong(), it.severity, it.matchedForm) },
            v.schemaValid, v.waypointClassHint, v.failMode, v.rulesetVersion.toLong(), v.coreVersion,
        )
    }

    override fun close() = core.close()
}
