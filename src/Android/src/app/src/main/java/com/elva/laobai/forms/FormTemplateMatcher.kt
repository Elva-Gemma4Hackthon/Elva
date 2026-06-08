/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.forms

import android.util.Log
import com.elva.laobai.models.FormFieldDef
import com.elva.laobai.models.FormTemplate
import com.elva.laobai.models.FormTemplateMatch
import com.elva.laobai.models.FormTemplates
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.models.UIElement

/**
 * Form Template Matcher — matches the current screen against
 * registered fixed form templates for the always-on form filling
 * assistant (Case 1).
 *
 * Matching strategy:
 * 1. Check if pageType is "form"
 * 2. Match pageKeywords against all UI text
 * 3. Calculate field coverage ratio (how many template fields are present)
 * 4. Detect blocked targets on the page
 * 5. Return best match with confidence score
 *
 * V1: Only handles fixed templates. No arbitrary form generalization.
 */
object FormTemplateMatcher {
    private const val TAG = "FormMatcher"

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /** Minimum keyword match ratio for a positive match. */
    private const val MIN_KEYWORD_RATIO = 0.3f

    /** Minimum field coverage ratio for a positive match. */
    private const val MIN_FIELD_COVERAGE = 0.5f

    /** Minimum overall confidence to return a match. */
    private const val MIN_CONFIDENCE = 0.4f

    /** All registered templates. */
    private val templates = mutableListOf<FormTemplate>()

    init {
        // Register V1 built-in templates
        templates.addAll(FormTemplates.ALL_TEMPLATES)
        logDebug("Registered ${templates.size} form templates")
    }

    /**
     * Match the current screen observation against all registered templates.
     *
     * @param observation The redacted screen observation from ScreenObserver.
     * @return FormTemplateMatch with the best matching template, or unmatched result.
     */
    fun match(observation: ScreenObservation): FormTemplateMatch {
        // Quick filter: only consider form-type pages
        if (observation.pageType != "form") {
            logDebug("Page type is ${observation.pageType}, not 'form' — skipping")
            return FormTemplateMatch(matched = false)
        }

        // Extract all text from the page for keyword matching
        val pageText = observation.uiElements.joinToString(" ") { it.text }.lowercase()

        // Extract editable fields as field candidates
        val editableFields = observation.uiElements.filter { it.isEditable }

        var bestMatch: FormTemplateMatch = FormTemplateMatch(matched = false)

        for (template in templates) {
            val templateMatch = matchTemplate(template, pageText, editableFields)
            if (templateMatch.matched && templateMatch.confidence > bestMatch.confidence) {
                bestMatch = templateMatch
            }
        }

        if (bestMatch.matched) {
            logDebug(
                "Matched template: ${bestMatch.template?.templateId} confidence=${bestMatch.confidence}",
            )
        } else {
            logDebug("No template matched for current page")
        }

        return bestMatch
    }

    /**
     * Match a single template against the page content.
     */
    private fun matchTemplate(
        template: FormTemplate,
        pageText: String,
        editableFields: List<UIElement>,
    ): FormTemplateMatch {
        // Step 1: Keyword match
        val keywordHits = template.pageKeywords.count { keyword ->
            pageText.contains(keyword.lowercase())
        }
        val keywordRatio = if (template.pageKeywords.isNotEmpty()) {
            keywordHits.toFloat() / template.pageKeywords.size
        } else 0f

        if (keywordRatio < MIN_KEYWORD_RATIO) {
            return FormTemplateMatch(matched = false)
        }

        // Step 2: Field coverage — how many template fields are on the page?
        val matchedFields = mutableListOf<FormFieldDef>()
        for (fieldDef in template.requiredFields) {
            val foundOnPage = editableFields.any { element ->
                val elementText = element.text.lowercase()
                val elementDesc = element.contentDescription?.lowercase() ?: ""
                elementText.contains(fieldDef.uiLabel.lowercase()) ||
                    elementDesc.contains(fieldDef.uiLabel.lowercase())
            }
            if (foundOnPage) {
                matchedFields.add(fieldDef)
            }
        }

        val fieldCoverage = if (template.requiredFields.isNotEmpty()) {
            matchedFields.size.toFloat() / template.requiredFields.size
        } else 0f

        // Step 3: Detect blocked targets on the page
        val blockedElementsPresent = template.blockedTargets.filter { blocked ->
            pageText.contains(blocked.lowercase())
        }

        // Step 4: Calculate overall confidence
        // Weight: keywords 30%, field coverage 70%
        val confidence = keywordRatio * 0.3f + fieldCoverage * 0.7f

        return if (confidence >= MIN_CONFIDENCE && fieldCoverage >= MIN_FIELD_COVERAGE) {
            FormTemplateMatch(
                matched = true,
                template = template,
                confidence = confidence,
                matchedFields = matchedFields,
                blockedElementsPresent = blockedElementsPresent,
            )
        } else {
            FormTemplateMatch(matched = false)
        }
    }

    /**
     * Get all registered templates.
     */
    fun getRegisteredTemplates(): List<FormTemplate> = templates.toList()

    /**
     * Register a new template dynamically (for V2 extension).
     */
    fun registerTemplate(template: FormTemplate) {
        templates.add(template)
        logDebug("Dynamically registered template: ${template.templateId}")
    }
}
