/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elva.laobai.memory.LocalUserMemory

/**
 * User Memory Settings Screen — lets elderly users input personal information
 * that is stored encrypted on-device for form filling and health consultation.
 *
 * Fields:
 * - Display name, Phone, Address, Emergency contact,
 *   Medical card label, Preferred hospital, Preferred department
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserMemorySettingsScreen(
    onBack: () -> Unit = {},
) {
    // Field states
    var displayName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var emergencyContact by remember { mutableStateOf("") }
    var medicalCard by remember { mutableStateOf("") }
    var preferredHospital by remember { mutableStateOf("") }
    var preferredDepartment by remember { mutableStateOf("") }

    // Authorization states
    var authDisplayName by remember { mutableStateOf(false) }
    var authPhone by remember { mutableStateOf(false) }
    var authAddress by remember { mutableStateOf(false) }
    var authEmergencyContact by remember { mutableStateOf(false) }
    var authMedicalCard by remember { mutableStateOf(false) }
    var authHospital by remember { mutableStateOf(false) }
    var authDepartment by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Load existing data
    LaunchedEffect(Unit) {
        val info = LocalUserMemory.getUserInfo()
        val K = LocalUserMemory.FieldKeys
        displayName = info[K.DISPLAY_NAME] ?: ""
        phone = info[K.PHONE_MASKED] ?: ""
        address = info[K.ADDRESS_LABEL] ?: ""
        emergencyContact = info[K.EMERGENCY_CONTACT_LABEL] ?: ""
        medicalCard = info[K.MEDICAL_CARD_LABEL] ?: ""
        preferredHospital = info[K.PREFERRED_HOSPITAL] ?: ""
        preferredDepartment = info[K.PREFERRED_DEPARTMENT] ?: ""

        authDisplayName = LocalUserMemory.isAuthorized(K.DISPLAY_NAME)
        authPhone = LocalUserMemory.isAuthorized(K.PHONE_MASKED)
        authAddress = LocalUserMemory.isAuthorized(K.ADDRESS_LABEL)
        authEmergencyContact = LocalUserMemory.isAuthorized(K.EMERGENCY_CONTACT_LABEL)
        authMedicalCard = LocalUserMemory.isAuthorized(K.MEDICAL_CARD_LABEL)
        authHospital = LocalUserMemory.isAuthorized(K.PREFERRED_HOSPITAL)
        authDepartment = LocalUserMemory.isAuthorized(K.PREFERRED_DEPARTMENT)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "个人信息",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text(
                    text = "填写后老白可以帮您自动填表、推荐科室。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "基础信息",
                subtitle = "只在你授权后，老白才会在相关场景中使用这些信息。",
            ) {
                MemoryFieldRow(
                    label = "您的名字",
                    value = displayName,
                    onValueChange = { displayName = it },
                    authorized = authDisplayName,
                    onAuthChange = { authDisplayName = it },
                )
                MemoryFieldRow(
                    label = "手机号码",
                    value = phone,
                    onValueChange = { phone = it },
                    authorized = authPhone,
                    onAuthChange = { authPhone = it },
                )
                MemoryFieldRow(
                    label = "家庭地址",
                    value = address,
                    onValueChange = { address = it },
                    authorized = authAddress,
                    onAuthChange = { authAddress = it },
                )
                MemoryFieldRow(
                    label = "紧急联系人",
                    value = emergencyContact,
                    onValueChange = { emergencyContact = it },
                    authorized = authEmergencyContact,
                    onAuthChange = { authEmergencyContact = it },
                )
                MemoryFieldRow(
                    label = "医保卡号",
                    value = medicalCard,
                    onValueChange = { medicalCard = it },
                    authorized = authMedicalCard,
                    onAuthChange = { authMedicalCard = it },
                )
            }

            SectionCard(title = "看病挂号偏好") {
                MemoryFieldRow(
                    label = "首选医院",
                    value = preferredHospital,
                    onValueChange = { preferredHospital = it },
                    authorized = authHospital,
                    onAuthChange = { authHospital = it },
                )
                MemoryFieldRow(
                    label = "常去科室",
                    value = preferredDepartment,
                    onValueChange = { preferredDepartment = it },
                    authorized = authDepartment,
                    onAuthChange = { authDepartment = it },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            SectionCard {
                Button(
                    onClick = {
                        isSaving = true
                        saveSuccess = false
                        val K = LocalUserMemory.FieldKeys
                        val fields = mutableMapOf<String, String>()
                        val authorizations = mutableMapOf<String, Boolean>()

                        fun addField(key: String, value: String, auth: Boolean) {
                            if (value.isNotBlank()) {
                                fields[key] = value
                                authorizations[key] = auth
                            }
                        }

                        addField(K.DISPLAY_NAME, displayName, authDisplayName)
                        addField(K.PHONE_MASKED, phone, authPhone)
                        addField(K.ADDRESS_LABEL, address, authAddress)
                        addField(K.EMERGENCY_CONTACT_LABEL, emergencyContact, authEmergencyContact)
                        addField(K.MEDICAL_CARD_LABEL, medicalCard, authMedicalCard)
                        addField(K.PREFERRED_HOSPITAL, preferredHospital, authHospital)
                        addField(K.PREFERRED_DEPARTMENT, preferredDepartment, authDepartment)

                        scope.launch {
                            LocalUserMemory.setFields(fields)
                            for ((key, auth) in authorizations) {
                                if (auth) LocalUserMemory.authorizeField(key)
                                else LocalUserMemory.revokeField(key)
                            }
                            isSaving = false
                            saveSuccess = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !isSaving,
                ) {
                    Text(
                        text = if (isSaving) "保存中..." else if (saveSuccess) "已保存" else "保存信息",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "所有信息都加密保存在您的手机上，不会上传到云端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MemoryFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    authorized: Boolean,
    onAuthChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(text = label, style = MaterialTheme.typography.bodyMedium) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp),
        ) {
            Checkbox(
                checked = authorized,
                onCheckedChange = onAuthChange,
            )
            Text(
                text = "允许老白使用此信息帮您填表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
