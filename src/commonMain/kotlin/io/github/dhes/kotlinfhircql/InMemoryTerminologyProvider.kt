package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.ValueSet
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.terminology.CodeSystemInfo
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo

/**
 * A [TerminologyProvider] over kotlin-fhir ValueSet resources, using each value set's expansion,
 * or the concepts listed in its compose when it has no expansion.
 */
class InMemoryTerminologyProvider(valueSets: List<ValueSet>) : TerminologyProvider {

    // Built on first use, so a value set this provider cannot expand fails only if CQL uses it.
    private val codesByUrl: Map<String, Lazy<List<Code>>> =
        valueSets.associate { vs -> (vs.url?.value ?: error("ValueSet ${vs.id} has no url")) to lazy { codesOf(vs) } }

    override fun `in`(code: Code, valueSet: ValueSetInfo): Boolean =
        codes(valueSet).any { it.code == code.code && (code.system == null || it.system == code.system) }

    override fun expand(valueSet: ValueSetInfo): Iterable<Code> = codes(valueSet)

    override fun lookup(code: Code, codeSystem: CodeSystemInfo): Code? = null

    private fun codes(valueSet: ValueSetInfo): List<Code> =
        codesByUrl[valueSet.id]?.value ?: throw IllegalArgumentException("Unknown value set ${valueSet.id}")

    private fun codesOf(vs: ValueSet): List<Code> {
        val expansion = vs.expansion
        if (expansion != null) return expansion.contains.flatMap(::codesOf)
        return vs.compose?.include.orEmpty().flatMap { include ->
            check(include.filter.isEmpty() && include.valueSet.isEmpty()) {
                "ValueSet ${vs.url?.value} needs a filter or another value set; only listed concepts are supported"
            }
            include.concept.map { Code().withSystem(include.system?.value).withVersion(include.version?.value).withCode(it.code.value).withDisplay(it.display?.value) }
        }
    }

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
