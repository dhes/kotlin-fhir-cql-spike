package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.cqframework.cql.shared.BigDecimal
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.runtime.List as CqlList

/** Step 1: one WHO definition over kotlin-fhir resources, checked against cqframework's HAPI resolver. */
class Step1Test {
    private val root = File(System.getProperty("user.dir"))
    private val modelInfoXml = File(root, "third_party/cqframework-quick-5.4.0/fhir-modelinfo-4.0.1.xml").readText()
    private val librarySources =
        File(root, "third_party/smart-immunizations-0.2.0/cql").listFiles()!!.filter { it.extension == "cql" }.map { it.readText() } +
            File(root, "third_party/cqframework-quick-5.4.0/FHIRHelpers-4.0.1.cql").readText()
    private val bundleJson = File(root, "fixtures/patients/step1-p1.json").readText()

    private val offsetHours = BigDecimal(java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds).divide(BigDecimal(3600))

    private fun kotlinFhirResources(): List<ClassInstance> {
        val bundle = Json.decodeFromString<Resource>(bundleJson) as Bundle
        val converter = KotlinFhirToCql(offsetHours)
        return bundle.entry.map { converter.convert(it.resource!!) }
    }

    private fun hapiResources(resolver: R4FhirModelResolver, context: FhirContext): List<ClassInstance> {
        val bundle = context.newJsonParser().parseResource(org.hl7.fhir.r4.model.Bundle::class.java, bundleJson)
        return bundle.entry.map { resolver.toCqlValue(it.resource)!! }
    }

    private fun ids(value: Value?): List<String> =
        (value as CqlList).map { (((it as ClassInstance).elements["id"] as ClassInstance).elements["value"]).toString() }

    @Test
    fun dosesAdministeredToPatient() {
        val expression = "Doses Administered to Patient"

        val ours = FhirCqlEvaluator(librarySources, modelInfoXml)
            .evaluate("IMMZElements", "p1", kotlinFhirResources(), listOf(expression))

        val hapiContext = FhirContext.forR4Cached()
        val hapiResolver = R4FhirModelResolver(hapiContext)
        val reference = FhirCqlEvaluator(librarySources, modelInfoXml, modelResolverFactory = { hapiResolver })
            .evaluate("IMMZElements", "p1", hapiResources(hapiResolver, hapiContext), listOf(expression))

        println("kotlin-fhir: ${ids(ours[expression])}")
        println("HAPI:        ${ids(reference[expression])}")
        assertEquals(listOf("'imm-completed'"), ids(ours[expression]))
        assertEquals(ids(reference[expression]), ids(ours[expression]))
    }
}
