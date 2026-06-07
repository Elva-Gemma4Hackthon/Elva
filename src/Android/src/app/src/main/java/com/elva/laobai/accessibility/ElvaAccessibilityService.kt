/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityService for Elva LaoBai.
 * Enables automated interaction with other apps on behalf of elderly users.
 *
 * Use cases:
 * - Pay utility bills (交电费)
 * - Book hospital appointments (挂号)
 * - Navigate complex app interfaces
 */
class ElvaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ElvaA11y"

        @Volatile
        var instance: ElvaAccessibilityService? = null
            private set

        /** Check if the accessibility service is currently running. */
        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Configure the service to monitor all apps
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        Log.d(TAG, "Elva Accessibility Service connected")

        // Start Always-On Sentinel for passive risk monitoring
        com.elva.laobai.sentinel.AlwaysOnSentinel.startMonitoring()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Feed events to the Always On Sentinel for passive risk monitoring
        event?.let { ae ->
            val eventText = ae.text?.joinToString(" ") ?: ""
            val packageName = ae.packageName?.toString() ?: ""
            com.elva.laobai.sentinel.AlwaysOnSentinel.onAccessibilityEvent(
                eventType = ae.eventType,
                packageName = packageName,
                text = eventText,
                className = ae.className?.toString() ?: "",
            )
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Elva Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Elva Accessibility Service destroyed")
    }

    // ===== Action Helpers =====

    /**
     * Find a node by text content and click it.
     * @param text The exact or partial text to find.
     * @param contains If true, matches nodes containing the text; if false, exact match.
     * @return true if a node was found and clicked.
     */
    fun clickByText(text: String, contains: Boolean = true): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            val targetNode = findClickableNode(node)
            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked node with text: $text")
                return true
            }
        }
        Log.w(TAG, "No clickable node found with text: $text")
        return false
    }

    /**
     * Find a node by view ID resource name and click it.
     * @param viewId The full resource ID (e.g., "com.example:id/button")
     * @return true if found and clicked.
     */
    fun clickById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            val targetNode = findClickableNode(node)
            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked node with ID: $viewId")
                return true
            }
        }
        Log.w(TAG, "No clickable node found with ID: $viewId")
        return false
    }

    /**
     * Type text into a focused editable field.
     * @param text The text to type.
     * @return true if the text was entered.
     */
    fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        // Find the currently focused editable node
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Typed text: $text")
            return true
        }
        Log.w(TAG, "No focused editable node found")
        return false
    }

    /**
     * Find an editable node by hint text and type into it.
     * @param hintText The hint or content description to find the field.
     * @param text The text to type.
     * @return true if successful.
     */
    fun typeInField(hintText: String, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(hintText)
        for (node in nodes) {
            // Look for editable parent or sibling
            var editableNode = findEditableNode(node.parent ?: node)
            if (editableNode == null) {
                editableNode = findEditableNode(node)
            }
            if (editableNode != null) {
                val args = android.os.Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
                editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Typed '$text' in field with hint: $hintText")
                return true
            }
        }
        Log.w(TAG, "No editable field found near: $hintText")
        return false
    }

    /**
     * Press the back button.
     */
    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "Pressed back")
    }

    /**
     * Press the home button.
     */
    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "Pressed home")
    }

    /**
     * Open the notifications shade.
     */
    fun openNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * Get the current package name of the foreground app.
     */
    fun getCurrentPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    // ===== Helper Methods =====

    /** Walk up the tree to find a clickable ancestor. */
    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        val parent = node.parent ?: return null
        if (parent.isClickable) return parent
        return findClickableNode(parent)
    }

    /** Find an editable node in the subtree. */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }
}
