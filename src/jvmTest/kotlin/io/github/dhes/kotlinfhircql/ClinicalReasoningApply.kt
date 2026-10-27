package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import java.io.File
import java.time.ZonedDateTime
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.CommunicationRequest
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.PlanDefinition
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.cql.LibraryEngine
import org.opencds.cqf.fhir.cr.CrSettings
import org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor
import org.opencds.cqf.fhir.utility.monad.Eithers
import org.opencds.cqf.fhir.utility.repository.InMemoryFhirRepository

/** The evaluation time clinical-reasoning's `LibraryEngine` uses when its caller gives none (see the copy in jvmTest). */
object ClinicalReasoningClock {
    @Volatile var now: ZonedDateTime? = null
}

/** What clinical-reasoning's `$apply` returned for one WHO scenario. */
data class CrApplyOutcome(
    val carePlan: CarePlan?,
    val contained: Int?,
    val guidance: String?,
    val error: String?,
)

/**
 * WHO's PlanDefinitions run through clinical-reasoning's `PlanDefinitionProcessor` (cqf-fhir-cr 4.13.0), over an
 * in-memory repository holding WHO's package 0.2.0 as published and one scenario's bundle, with the `Today`
 * parameter WHO's test sends and CQL's Now() at noon on that day, as in steps 4 and 15.
 */
object ClinicalReasoningApply {
    val context: FhirContext = FhirContext.forR4Cached()

    /** Every resource in WHO's package, read by HAPI. */
    private val packageResources: List<Resource> by lazy {
        File(PROJECT_DIR, "build/who-package/package").listFiles()!!
            .filter { it.isFile && it.name.endsWith(".json") && it.name != "package.json" && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .map { context.newJsonParser().parseResource(it.readText()) as Resource }
    }

    /** One settings object for every scenario, so clinical-reasoning's library cache is shared. */
    private val settings: CrSettings by lazy { CrSettings.getDefault() }

    /**
     * The CarePlan as a server would return it: written to JSON and read back. HAPI writes one contained resource per
     * id, so a resource contained twice, or two with the same id, come back once.
     */
    fun roundTrip(carePlan: CarePlan): CarePlan =
        context.newJsonParser().parseResource(CarePlan::class.java, context.newJsonParser().encodeResourceToString(carePlan))

    /** The payload text of the first contained CommunicationRequest. */
    fun guidance(carePlan: CarePlan): String? =
        carePlan.contained.filterIsInstance<CommunicationRequest>().firstNotNullOfOrNull { it.payload.firstOrNull()?.contentStringType?.value }

    /**
     * Applies the scenario's PlanDefinition; the CarePlan is as served (see [roundTrip]) unless [roundTrip] is false.
     * CQL's Now() is noon on the scenario's Today unless [pinNow] is false, when it is the wall clock.
     */
    fun apply(scenario: WhoScenario, roundTrip: Boolean = true, pinNow: Boolean = true): CrApplyOutcome =
        try {
            val data = context.newJsonParser().parseResource(Bundle::class.java, WhoSuite.bundleJson(scenario))
            val bundle = Bundle().setType(Bundle.BundleType.COLLECTION)
            (packageResources + data.entry.map { it.resource }).forEach { bundle.addEntry().resource = it }
            val repository = InMemoryFhirRepository(context, bundle)
            val plan = packageResources.filterIsInstance<PlanDefinition>().single { it.idPart == scenario.planDefinition }
            val (y, m, d) = scenario.today.split("-").map(String::toInt)
            ClinicalReasoningClock.now = if (pinNow) noonOn(y, m, d) else null
            val parameters = Parameters()
            parameters.addParameter().setName("Today").setValue(DateType(scenario.today))
            val processor = PlanDefinitionProcessor(repository, settings)
            val result: IBaseResource =
                processor.apply(
                    Eithers.forRight3(plan),
                    "Patient/${scenario.patient}",
                    null, null, null, null, null, null, null, null,
                    parameters,
                    null,
                    null,
                    LibraryEngine(repository, settings.evaluationSettings),
                )
            val inMemory = result as CarePlan
            val carePlan = if (roundTrip) roundTrip(inMemory) else inMemory
            CrApplyOutcome(carePlan, carePlan.contained.size, guidance(carePlan), null)
        } catch (e: Throwable) {
            CrApplyOutcome(null, null, null, "${e::class.simpleName}: ${e.message?.lineSequence()?.first()}")
        } finally {
            ClinicalReasoningClock.now = null
        }
}
