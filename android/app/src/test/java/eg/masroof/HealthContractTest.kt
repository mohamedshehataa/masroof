package eg.masroof

import eg.masroof.api.models.Health
import eg.masroof.data.remote.apiJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract `Health` example (components.schemas.Health.examples[0]) and its required `version` field. */
class HealthContractTest {
    private val example =
        """
        {"status":"UP","version":"0.1.0","gitSha":"1a2b3c4","buildTime":"2026-10-23T10:00:00Z",
         "time":"2026-10-23T12:34:56Z","checks":{"db":"UP"}}
        """.trimIndent()

    @Test
    fun `REQ-OPS-055 deserialises the contract Health example`() {
        val health = apiJson.decodeFromString(Health.serializer(), example)
        assertEquals("0.1.0", health.version)
        assertEquals("1a2b3c4", health.gitSha)
        assertEquals(setOf("db"), health.checks?.keys)
    }

    @Test
    fun `REQ-OPS-055 a Health without the required version fails instead of passing silently`() {
        val withoutVersion = example.replace("\"version\":\"0.1.0\",", "")
        val error = runCatching { apiJson.decodeFromString(Health.serializer(), withoutVersion) }.exceptionOrNull()
        assertTrue("expected a SerializationException, got $error", error is SerializationException)
    }
}
