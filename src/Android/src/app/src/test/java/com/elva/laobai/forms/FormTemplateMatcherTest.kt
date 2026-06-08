package com.elva.laobai.forms

import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.models.UIElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormTemplateMatcherTest {

    @Test
    fun `matches community event signup form`() {
        val observation = ScreenObservation(
            pageType = "form",
            uiElements = listOf(
                UIElement(type = "text", text = "社区活动报名"),
                UIElement(type = "input", text = "姓名", isEditable = true),
                UIElement(type = "input", text = "手机号", isEditable = true),
                UIElement(type = "input", text = "家庭住址", isEditable = true),
                UIElement(type = "button", text = "提交", isClickable = true),
            ),
            sensitiveFieldCategories = emptyList(),
            cloudSafe = true,
        )

        val match = FormTemplateMatcher.match(observation)

        assertTrue(match.matched)
        assertEquals("community_event_signup", match.template?.templateId)
        assertTrue(match.blockedElementsPresent.contains("提交"))
    }

    @Test
    fun `does not match non form page`() {
        val observation = ScreenObservation(
            pageType = "chat",
            uiElements = listOf(
                UIElement(type = "text", text = "你好"),
                UIElement(type = "button", text = "发送", isClickable = true),
            ),
            sensitiveFieldCategories = emptyList(),
            cloudSafe = true,
        )

        val match = FormTemplateMatcher.match(observation)

        assertFalse(match.matched)
    }
}
