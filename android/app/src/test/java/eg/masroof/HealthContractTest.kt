package eg.masroof

import eg.masroof.api.models.Health
import eg.masroof.data.remote.apiJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** REQ-OPS-055: the `Health` example is read from contracts/openapi.yaml itself, never copied here. */
class HealthContractTest {
    @Suppress("UNCHECKED_CAST")
    private fun contractExample(): Map<String, Any?> {
        val file = File("../../contracts/openapi.yaml")
        assertTrue("contract not found at ${file.absolutePath}", file.exists())
        val doc = file.reader().use { Yaml().load<Map<String, Any?>>(it) }

        fun child(
            node: Any?,
            key: String,
        ): Any? = (node as Map<*, *>)[key]
        val schema = child(child(child(doc, "components"), "schemas"), "Health")
        return (child(schema, "examples") as List<*>)[0] as Map<String, Any?>
    }

    private fun toJson(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    @Test
    fun `REQ-OPS-055 deserialises the contract Health example`() {
        val example = contractExample()
        val health = apiJson.decodeFromJsonElement(Health.serializer(), toJson(example))
        assertEquals(example["version"].toString(), health.version)
        assertEquals(example["gitSha"].toString(), health.gitSha)
        assertEquals(setOf("db"), health.checks?.keys)
    }

    @Test
    fun `REQ-OPS-055 a Health without the required version fails instead of passing silently`() {
        val withoutVersion = contractExample() - "version"
        val error =
            runCatching { apiJson.decodeFromJsonElement(Health.serializer(), toJson(withoutVersion)) }.exceptionOrNull()
        assertTrue("expected a SerializationException, got $error", error is SerializationException)
    }
}
