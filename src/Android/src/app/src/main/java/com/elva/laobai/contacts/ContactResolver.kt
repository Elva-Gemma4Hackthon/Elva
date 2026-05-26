/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.contacts

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

/**
 * Contact resolver for Elva LaoBai.
 * Matches relationship keywords (son, daughter, etc.) to phone contacts.
 */
object ContactResolver {
    private const val TAG = "ElvaContacts"

    /** Relationship keyword mappings (Chinese & English). */
    private val RELATIONSHIP_MAP = mapOf(
        // Chinese
        "儿子" to listOf("儿", "儿子"),
        "女儿" to listOf("女", "女儿"),
        "老伴" to listOf("老伴", "老公", "老婆", "妻子", "丈夫", "爱人"),
        "妈妈" to listOf("妈", "妈妈", "母亲"),
        "爸爸" to listOf("爸", "爸爸", "父亲"),
        "孙子" to listOf("孙", "孙子"),
        "孙女" to listOf("孙女"),
        "哥哥" to listOf("哥", "哥哥"),
        "弟弟" to listOf("弟", "弟弟"),
        "姐姐" to listOf("姐", "姐姐"),
        "妹妹" to listOf("妹", "妹妹"),
        // English
        "son" to listOf("son"),
        "daughter" to listOf("daughter"),
        "husband" to listOf("husband"),
        "wife" to listOf("wife"),
        "mom" to listOf("mom", "mother", "mum"),
        "dad" to listOf("dad", "father", "pop"),
        "brother" to listOf("brother"),
        "sister" to listOf("sister"),
        "grandson" to listOf("grandson"),
        "granddaughter" to listOf("granddaughter"),
    )

    data class Contact(
        val name: String,
        val phoneNumber: String,
        val relationship: String? = null,
    )

    /**
     * Search contacts by name or phone number.
     * @param query The search query (name, phone number, or relationship keyword).
     * @return List of matching contacts.
     */
    fun searchContacts(context: Context, query: String): List<Contact> {
        val results = mutableListOf<Contact>()

        // First, try to match by relationship keyword
        val relationship = matchRelationship(query)
        if (relationship != null) {
            results.addAll(searchByRelationship(context, relationship))
            if (results.isNotEmpty()) return results
        }

        // Fallback: search by name or phone number
        results.addAll(searchByNameOrPhone(context, query))
        return results
    }

    /**
     * Find the best matching contact for a voice command.
     * Returns the first match or null.
     */
    fun findBestMatch(context: Context, query: String): Contact? {
        return searchContacts(context, query).firstOrNull()
    }

    /**
     * Try to match the query to a known relationship keyword.
     */
    private fun matchRelationship(query: String): String? {
        val normalized = query.lowercase().trim()
        for ((relationship, keywords) in RELATIONSHIP_MAP) {
            for (keyword in keywords) {
                if (normalized.contains(keyword)) {
                    return relationship
                }
            }
        }
        return null
    }

    /**
     * Search contacts by relationship label in contact notes or relation.
     * Falls back to fuzzy name matching.
     */
    private fun searchByRelationship(context: Context, relationship: String): List<Contact> {
        val results = mutableListOf<Contact>()
        val keywords = RELATIONSHIP_MAP[relationship] ?: listOf(relationship)

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val phoneIdx = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

                while (it.moveToNext()) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else ""
                    val phone = if (phoneIdx >= 0) it.getString(phoneIdx) else ""
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        // Check if the contact name contains the relationship keyword
                        // e.g., a contact named "儿子 小明" or "Daughter Alice"
                        for (keyword in keywords) {
                            if (name.contains(keyword, ignoreCase = true)) {
                                results.add(
                                    Contact(name = name, phoneNumber = phone, relationship = relationship)
                                )
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts by relationship", e)
        }

        return results
    }

    /**
     * Search contacts by display name or phone number.
     */
    private fun searchByNameOrPhone(context: Context, query: String): List<Contact> {
        val results = mutableListOf<Contact>()

        try {
            val selection = """
                ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?
                OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?
            """.trimIndent()
            val selectionArgs = arrayOf("%$query%", "%$query%")

            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                selection,
                selectionArgs,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val phoneIdx = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

                while (it.moveToNext()) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else ""
                    val phone = if (phoneIdx >= 0) it.getString(phoneIdx) else ""
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        results.add(Contact(name = name, phoneNumber = phone))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
        }

        return results
    }
}
