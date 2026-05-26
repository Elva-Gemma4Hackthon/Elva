/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toUri
import com.google.ai.edge.gallery.notifications.NotificationScheduleManagerEntryPoint
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.EntryPointAccessors
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  val extra_email: String,
  val extra_subject: String,
  val extra_text: String,
)

@JsonClass(generateAdapter = true)
data class SendSmsParams(val phone_number: String, val sms_body: String)

@JsonClass(generateAdapter = true)
data class CreateCalendarEventParams(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true) data class ReadCalendarEventsParams(val date: String)

@JsonClass(generateAdapter = true)
data class CalendarEventDto(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true)
data class ReadCalendarEventsResponse(val events: List<CalendarEventDto>)

@JsonClass(generateAdapter = true)
data class MakePhoneCallParams(val phone_number: String)

@JsonClass(generateAdapter = true)
data class OpenPhotosParams(val album_name: String? = null)

@JsonClass(generateAdapter = true)
data class PayElectricBillParams(
    val account_number: String? = null,
    val city: String? = null,
)

@JsonClass(generateAdapter = true)
data class PayWaterBillParams(
    val account_number: String? = null,
    val city: String? = null,
)

@JsonClass(generateAdapter = true)
data class BookHospitalParams(
    val hospital: String? = null,
    val department: String? = null,
    val date: String? = null,
)

enum class IntentAction(val action: String) {
  SEND_EMAIL("send_email"),
  SEND_SMS("send_sms"),
  CREATE_CALENDAR_EVENT("create_calendar_event"),
  READ_CALENDAR_EVENTS("read_calendar_events"),
  GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
  SCHEDULE_NOTIFICATION("schedule_notification"),
  MAKE_PHONE_CALL("make_phone_call"),
  OPEN_PHOTOS("open_photos"),
  OPEN_CAMERA("open_camera"),
  PAY_ELECTRIC_BILL("pay_electric_bill"),
  PAY_WATER_BILL("pay_water_bill"),
  BOOK_HOSPITAL("book_hospital");

  companion object {
    fun from(action: String): IntentAction? = entries.find { it.action == action }
  }
}

@JsonClass(generateAdapter = true)
data class ScheduleNotificationParams(
  val title: String,
  val message: String,
  val hour: Int,
  val minute: Int,
  val deeplink: String? = null,
  val task_id: String? = null,
  val model_name: String? = null,
  val year: Int? = null,
  val month: Int? = null,
  val day: Int? = null,
  val repeat_daily: Boolean? = null,
)

object IntentHandler {
  private const val TAG = "IntentHandler"

  suspend fun handleAction(
    context: Context,
    action: String,
    parameters: String,
    // requestPermission is a suspend function that takes a permission string and returns true if
    // the permission is granted, false otherwise.
    requestPermission: suspend (String) -> Boolean,
  ): String {
    return when (IntentAction.from(action)) {
      IntentAction.SEND_EMAIL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendEmailParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val intent =
              Intent(Intent.ACTION_SEND).apply {
                data = "mailto:".toUri()
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(params.extra_email))
                putExtra(Intent.EXTRA_SUBJECT, params.extra_subject)
                putExtra(Intent.EXTRA_TEXT, params.extra_text)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_email parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SEND_SMS -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val uri = "smsto:${params.phone_number}".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.putExtra("sms_body", params.sms_body)
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CREATE_CALENDAR_EVENT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateCalendarEventParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTimeMillis = format.parse(params.begin_time)?.time ?: 0L
            val endTimeMillis = format.parse(params.end_time)?.time ?: 0L
            val intent =
              Intent(Intent.ACTION_INSERT).apply {
                data = Events.CONTENT_URI
                putExtra(Events.TITLE, params.title)
                putExtra(Events.DESCRIPTION, params.description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.READ_CALENDAR_EVENTS -> {
        readCalendarEvents(context, parameters, requestPermission)
      }
      IntentAction.GET_CURRENT_DATE_AND_TIME -> {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())
        Log.d(
          TAG,
          "get_current_date_and_time via handleAction. Current date and time: $currentDateAndTime",
        )
        currentDateAndTime
      }
      IntentAction.SCHEDULE_NOTIFICATION -> {
        scheduleNotification(context, parameters)
      }
      IntentAction.MAKE_PHONE_CALL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(MakePhoneCallParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val uri = "tel:${params.phone_number}".toUri()
            val intent = Intent(Intent.ACTION_CALL, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse make_phone_call parameters: $parameters")
            "failed"
          }
        } catch (e: SecurityException) {
          Log.e(TAG, "CALL_PHONE permission denied", e)
          // Fallback: open dialer instead of direct call
          try {
            val moshi = Moshi.Builder().build()
            val jsonAdapter = moshi.adapter(MakePhoneCallParams::class.java)
            val params = jsonAdapter.fromJson(parameters)
            if (params != null) {
              val uri = "tel:${params.phone_number}".toUri()
              val intent = Intent(Intent.ACTION_DIAL, uri)
              context.startActivity(intent)
              "succeeded"
            } else {
              "failed"
            }
          } catch (e2: Exception) {
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to make phone call: $parameters", e)
          "failed"
        }
      }
      IntentAction.OPEN_PHOTOS -> {
        try {
          val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(intent)
          "succeeded"
        } catch (e: Exception) {
          Log.e(TAG, "Failed to open photos", e)
          "failed"
        }
      }
      IntentAction.OPEN_CAMERA -> {
        try {
          val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
          "succeeded"
        } catch (e: Exception) {
          Log.e(TAG, "Failed to open camera", e)
          "failed"
        }
      }
      IntentAction.PAY_ELECTRIC_BILL -> {
        executeA11yTask(
          taskType = com.elva.laobai.accessibility.A11yTaskExecutor.TaskType.PAY_ELECTRIC_BILL,
          parameters = parameters,
          adapterClass = PayElectricBillParams::class.java,
          paramToMap = { mapOf("account_number" to (it.account_number ?: ""), "city" to (it.city ?: "")) },
        )
      }
      IntentAction.PAY_WATER_BILL -> {
        executeA11yTask(
          taskType = com.elva.laobai.accessibility.A11yTaskExecutor.TaskType.PAY_WATER_BILL,
          parameters = parameters,
          adapterClass = PayWaterBillParams::class.java,
          paramToMap = { mapOf("account_number" to (it.account_number ?: ""), "city" to (it.city ?: "")) },
        )
      }
      IntentAction.BOOK_HOSPITAL -> {
        executeA11yTask(
          taskType = com.elva.laobai.accessibility.A11yTaskExecutor.TaskType.BOOK_HOSPITAL,
          parameters = parameters,
          adapterClass = BookHospitalParams::class.java,
          paramToMap = { mapOf("hospital" to (it.hospital ?: ""), "department" to (it.department ?: ""), "date" to (it.date ?: "")) },
        )
      }
      null -> "failed"
    }
  }

  suspend fun readCalendarEvents(
    context: Context,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    if (
      checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      val granted = requestPermission(android.Manifest.permission.READ_CALENDAR)
      if (!granted) {
        Log.e(TAG, "READ_CALENDAR permission denied by user")
        return "failed: READ_CALENDAR permission denied by user"
      }
    }

    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ReadCalendarEventsParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateObj = format.parse(params.date)
        if (dateObj != null) {
          val cal =
            Calendar.getInstance().apply {
              timeInMillis = dateObj.time
              set(Calendar.HOUR_OF_DAY, 0)
              set(Calendar.MINUTE, 0)
              set(Calendar.SECOND, 0)
              set(Calendar.MILLISECOND, 0)
            }
          val startOfDayMillis = cal.timeInMillis

          cal.apply {
            add(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MILLISECOND, -1)
          }
          val endOfDayMillis = cal.timeInMillis

          val projection =
            arrayOf(Instances.TITLE, Instances.DESCRIPTION, Instances.BEGIN, Instances.END)

          val builder = Instances.CONTENT_URI.buildUpon()
          android.content.ContentUris.appendId(builder, startOfDayMillis)
          android.content.ContentUris.appendId(builder, endOfDayMillis)

          val cursor =
            context.contentResolver.query(
              builder.build(),
              projection,
              null,
              null,
              "${Instances.BEGIN} ASC",
            )

          val eventsList = mutableListOf<CalendarEventDto>()
          cursor?.use { c ->
            val titleIdx = c.getColumnIndex(Instances.TITLE)
            val descIdx = c.getColumnIndex(Instances.DESCRIPTION)
            val startIdx = c.getColumnIndex(Instances.BEGIN)
            val endIdx = c.getColumnIndex(Instances.END)
            val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            while (c.moveToNext()) {
              val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
              val desc = if (descIdx >= 0) c.getString(descIdx) ?: "" else ""
              val start = if (startIdx >= 0) c.getLong(startIdx) else 0L
              val end = if (endIdx >= 0) c.getLong(endIdx) else 0L
              eventsList.add(
                CalendarEventDto(
                  title = title,
                  description = desc,
                  begin_time = if (start > 0) timeFormat.format(Date(start)) else "",
                  end_time = if (end > 0) timeFormat.format(Date(end)) else "",
                )
              )
            }
          }
          val responseAdapter = moshi.adapter(ReadCalendarEventsResponse::class.java)
          return responseAdapter.toJson(ReadCalendarEventsResponse(eventsList))
        } else {
          Log.e(TAG, "Failed to parse read_calendar_events date: ${params.date}")
          return "failed"
        }
      } else {
        Log.e(TAG, "Failed to parse read_calendar_events parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read calendar events: $parameters", e)
      return "failed: ${e.message}"
    }
  }

  fun scheduleNotification(context: Context, parameters: String): String {
    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ScheduleNotificationParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val notificationProtoBuilder =
          ScheduledNotification.newBuilder()
            .setId(java.util.UUID.randomUUID().toString())
            .setTitle(params.title)
            .setMessage(params.message)
            .setHour(params.hour)
            .setMinute(params.minute)
            .setChannelId("agent_skill_tasks_channel")
            .setChannelName("Agent Skill Task")
        if (params.deeplink != null) {
          notificationProtoBuilder.setDeeplink(params.deeplink)
        } else if (params.task_id != null && params.model_name != null) {
          val uri =
            "com.elva.laobai://model/${params.task_id}/${params.model_name}"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else if (params.task_id != null) {
          val uri =
            "com.elva.laobai://${params.task_id}/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else {
          val fallbackUri =
            "com.elva.laobai://llm_agent_chat/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting fallback deeplink to: $fallbackUri")
          notificationProtoBuilder.setDeeplink(fallbackUri)
        }
        if (params.year != null) {
          notificationProtoBuilder.setYear(params.year)
        }
        if (params.month != null) {
          notificationProtoBuilder.setMonth(params.month)
        }
        if (params.day != null) {
          notificationProtoBuilder.setDay(params.day)
        }
        if (params.repeat_daily != null) {
          notificationProtoBuilder.setRepeatDaily(params.repeat_daily)
        }

        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        val success =
          entryPoint
            .notificationScheduleManager()
            .scheduleNotification(notificationProtoBuilder.build())
        if (!success) {
          return "failed"
        }
        return "succeeded"
      } else {
        Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters", e)
      return "failed"
    }
  }

  /**
   * Execute an accessibility automation task asynchronously.
   * Returns "succeeded" immediately after launching the task.
   * The actual result is delivered via A11yTaskExecutor.state flow.
   */
  private fun <T> executeA11yTask(
    taskType: com.elva.laobai.accessibility.A11yTaskExecutor.TaskType,
    parameters: String,
    adapterClass: Class<T>,
    paramToMap: (T) -> Map<String, String>,
  ): String {
    return try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(adapterClass)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val paramMap = paramToMap(params)
        // Launch the a11y task — result is async via A11yTaskExecutor.state
        com.elva.laobai.accessibility.A11yTaskExecutor.execute(
          taskType = taskType,
          params = paramMap,
          context = com.google.ai.edge.gallery.GalleryApplication.appContext
            ?: return "failed: no application context",
          onComplete = { success, message ->
            Log.d(TAG, "A11y task ${taskType.key} completed: success=$success msg=$message")
          },
        )
        "succeeded"
      } else {
        Log.e(TAG, "Failed to parse ${taskType.key} parameters: $parameters")
        "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to execute ${taskType.key}", e)
      "failed"
    }
  }
}
