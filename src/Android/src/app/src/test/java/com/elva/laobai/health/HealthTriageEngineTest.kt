package com.elva.laobai.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTriageEngineTest {

    @Test
    fun `stomach discomfort builds redacted cloud request`() {
        HealthTriageEngine.reset()
        HealthTriageEngine.startConsultation("我胃不舒服")
        HealthTriageEngine.processUserResponse("两天了，有点恶心")

        val request = HealthTriageEngine.buildCloudRequest()

        assertEquals("health_consultation", request.caseType)
        assertEquals("strict", request.redactionLevel)
        assertTrue(request.cloudSafe)
        assertTrue(request.healthSummary?.symptoms?.contains("stomach") == true)
        assertFalse(request.healthSummary?.summaryText?.contains("身份证") == true)
    }

    @Test
    fun `local fallback recommends gastroenterology for stomach symptoms`() {
        val response = HealthCloudPlanner.planLocalFallback(
            HealthTriageEngine.buildCloudRequest().copy(
                healthSummary = com.elva.laobai.models.HealthTriageSummary(
                    ageBand = "70s",
                    symptoms = listOf("stomach"),
                    duration = "2_days",
                    severity = "moderate",
                    riskFlags = emptyList(),
                    summaryText = "redacted",
                ),
            ),
        )

        assertEquals("消化内科", response.recommendedDepartment)
        assertTrue(response.requiresConfirmation)
    }
}
