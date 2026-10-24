package io.github.dhes.kotlinfhircql

import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.cqframework.cql.cql2elm.ucum.defaultLazyUcumService

/** Records each quantity case's value with cqframework's UCUM service on the JVM, for [QuantityCasesTest] on the other targets. */
class Step14Test {
    @Test
    fun recordQuantityResults() {
        val results = QuantityCases.evaluate(defaultLazyUcumService).toSortedMap()
        for ((key, value) in results) {
            val expected = QuantityCases.cases.getValue(key).expected
            println("$key: ${QuantityCases.cases.getValue(key).expression}\n    JVM: $value" + if (expected != null) "\n    test file: $expected" else "")
        }
        println("${results.size} cases, ${results.values.count { it.startsWith("error: ") }} errors")
        val file = File(PROJECT_DIR, QuantityCases.EXPECTED)
        file.parentFile.mkdirs()
        file.writeText(Json { prettyPrint = true }.encodeToString<Map<String, String>>(results) + "\n")
    }
}
