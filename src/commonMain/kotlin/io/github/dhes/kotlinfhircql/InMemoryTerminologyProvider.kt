package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.CodeSystem
import dev.ohs.fhir.model.r4.ValueSet
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.terminology.CodeSystemInfo
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo

/**
 * A [TerminologyProvider] over kotlin-fhir ValueSet and CodeSystem resources. A value set's codes are its expansion,
 * or, when it has none, the concepts and value sets its compose includes; codes with no system or no code are left
 * out, and the rest sorted by code, as in clinical-reasoning's `RepositoryTerminologyProvider`.
 *
 * @param codeSystems the code systems [lookup] answers from; a code system not among them is answered as
 *   clinical-reasoning answers every code system, by the code's system and version alone
 */
class InMemoryTerminologyProvider(valueSets: List<ValueSet>, codeSystems: List<CodeSystem> = emptyList()) :
    TerminologyProvider {

    // Built on first use, so a value set this provider cannot expand fails only if CQL uses it.
    private val valueSetsByUrl: Map<String, List<Pair<String?, Lazy<List<Code>>>>> =
        valueSets
            .groupBy { vs -> vs.url?.value ?: error("ValueSet ${vs.id} has no url") }
            .mapValues { (_, versions) -> versions.map { vs -> vs.version?.value to lazy { codesOf(vs) } } }

    private val codeSystemsByUrl: Map<String, List<CodeSystem>> =
        codeSystems.groupBy { cs -> cs.url?.value ?: error("CodeSystem ${cs.id} has no url") }

    /** The number of value sets this provider holds. */
    val size: Int get() = valueSetsByUrl.values.sumOf { it.size }

    /**
     * Whether [code] is in [valueSet], comparing code and system; a code with no system matches on its code alone, and
     * fails when the value set has codes from more than one system, as [TerminologyProvider.in] requires.
     */
    override fun `in`(code: Code, valueSet: ValueSetInfo): Boolean {
        val codes = codes(valueSet)
        if (code.system == null) {
            require(codes.mapTo(mutableSetOf()) { it.system }.size <= 1) {
                "The 'in' operation is ambiguous because the code system is not provided and the value set " +
                    "${valueSet.id} contains codes from multiple code systems"
            }
        }
        return codes.any { it.code == code.code && (code.system == null || it.system == code.system) }
    }

    override fun expand(valueSet: ValueSetInfo): Iterable<Code> = codes(valueSet)

    /**
     * The code in [codeSystem] equal to [code], with its display, from a complete CodeSystem resource for it;
     * without one, [code] itself when its system and version match [codeSystem], as clinical-reasoning answers.
     */
    override fun lookup(code: Code, codeSystem: CodeSystemInfo): Code? {
        if (code.system == null || code.system != codeSystem.id) return null
        if (code.version != null && code.version != codeSystem.version) return null
        val resource = codeSystem(codeSystem) ?: return code
        if (resource.content.value?.code != "complete") return code
        val concept = concepts(resource.concept).firstOrNull { it.code.value == code.code } ?: return null
        return Code()
            .withCode(code.code)
            .withSystem(code.system)
            .withVersion(resource.version?.value ?: code.version)
            .withDisplay(concept.display?.value ?: code.display)
    }

    private fun codeSystem(info: CodeSystemInfo): CodeSystem? {
        val versions = codeSystemsByUrl[info.id] ?: return null
        return if (info.version != null) versions.firstOrNull { it.version?.value == info.version }
        else versions.singleOrNull()
    }

    private fun concepts(concepts: List<CodeSystem.Concept>): List<CodeSystem.Concept> =
        concepts.flatMap { listOf(it) + concepts(it.concept) }

    /** The codes of the value set [info] names, by url and, when given, version. */
    private fun codes(info: ValueSetInfo): List<Code> {
        val versions = valueSetsByUrl[info.id] ?: throw IllegalArgumentException("Unknown value set ${info.id}")
        val match =
            if (info.version != null) {
                versions.firstOrNull { it.first == info.version }
                    ?: throw IllegalArgumentException("Unknown value set ${info.id}|${info.version}")
            } else {
                require(versions.size == 1) { "Value set ${info.id} has ${versions.size} versions and none was named" }
                versions.single()
            }
        return match.second.value
    }

    private fun codesOf(vs: ValueSet): List<Code> {
        val expansion = vs.expansion
        val codes =
            if (expansion != null) {
                expansion.contains.flatMap(::codesOf)
            } else {
                val compose = vs.compose
                check(compose == null || compose.exclude.isEmpty()) { "ValueSet ${vs.url?.value} has an exclude; not supported" }
                compose?.include.orEmpty().flatMap { include ->
                    check(include.filter.isEmpty()) { "ValueSet ${vs.url?.value} needs a filter; not supported" }
                    check(include.valueSet.isEmpty() || (include.system == null && include.concept.isEmpty())) {
                        "ValueSet ${vs.url?.value} includes value sets together with a system; not supported"
                    }
                    if (include.valueSet.isNotEmpty()) {
                        // The codes in every value set named: FHIR intersects the value sets of one include.
                        include.valueSet
                            .map { canonical -> codes(valueSetInfo(canonical.value ?: error("Empty valueSet in ${vs.url?.value}"))) }
                            .reduce { a, b -> a.filter { code -> b.any { it.code == code.code && it.system == code.system } } }
                    } else {
                        include.concept.map {
                            Code()
                                .withSystem(include.system?.value)
                                .withVersion(include.version?.value)
                                .withCode(it.code.value)
                                .withDisplay(it.display?.value)
                        }
                    }
                }
            }
        return codes.filter { it.code != null && it.system != null }.sortedBy { it.code }
    }

    private fun valueSetInfo(canonical: String): ValueSetInfo =
        ValueSetInfo().withId(canonical.substringBefore('|')).withVersion(canonical.substringAfter('|', "").ifEmpty { null })

    private fun codesOf(contains: ValueSet.Expansion.Contains): List<Code> {
        val own =
            if (contains.code != null && contains.abstract?.value != true) {
                listOf(Code().withSystem(contains.system?.value).withVersion(contains.version?.value).withCode(contains.code?.value).withDisplay(contains.display?.value))
            } else {
                emptyList()
            }
        return own + contains.contains.flatMap(::codesOf)
    }
}
