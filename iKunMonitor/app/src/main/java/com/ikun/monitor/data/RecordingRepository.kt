package com.ikun.monitor.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MetricSample(
    val timestampEpochMs: Long,
    val values: Map<String, Double>,
)

data class RecordingSession(
    val id: String,
    val name: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val samplingMs: Int,
    val samples: List<MetricSample>,
    // v1.1: 会话元信息（用于历史列表展示/对齐 metrics.md）
    val targetAppLabel: String? = null,
    val targetAppPackage: String? = null,
    val metricKeys: List<String> = emptyList(),
)

object RecordingRepository {
    private const val FILE_NAME = "recording_sessions_v1.json"
    private val ioLock = Any()
    private var initialized = false

    val sessions = mutableStateListOf<RecordingSession>()

    fun init(appContext: Context) {
        if (initialized) return
        initialized = true

        val file = File(appContext.filesDir, FILE_NAME)
        if (!file.exists()) return

        try {
            val text = file.readText()
            val arr = JSONArray(text)
            val restored = ArrayList<RecordingSession>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                val start = obj.optLong("startEpochMs")
                val end = obj.optLong("endEpochMs")
                val samplingMs = obj.optInt("samplingMs")

                val targetAppLabel = obj.optString("targetAppLabel").ifBlank { null }
                val targetAppPackage = obj.optString("targetAppPackage").ifBlank { null }

                val metricKeysArr = obj.optJSONArray("metricKeys") ?: JSONArray()
                val metricKeys = ArrayList<String>(metricKeysArr.length())
                for (k in 0 until metricKeysArr.length()) {
                    val key = metricKeysArr.optString(k)
                    if (key.isNotBlank()) metricKeys.add(key)
                }

                val samplesArr = obj.optJSONArray("samples") ?: JSONArray()
                val samples = ArrayList<MetricSample>(samplesArr.length())
                for (j in 0 until samplesArr.length()) {
                    val sObj = samplesArr.optJSONObject(j) ?: continue
                    val ts = sObj.optLong("t")
                    val valuesObj = sObj.optJSONObject("v") ?: JSONObject()
                    val values = LinkedHashMap<String, Double>()
                    val itKeys = valuesObj.keys()
                    while (itKeys.hasNext()) {
                        val k = itKeys.next()
                        val v = valuesObj.optDouble(k)
                        values[k] = v
                    }
                    samples.add(MetricSample(timestampEpochMs = ts, values = values))
                }

                if (id.isNotBlank()) {
                    restored.add(
                        RecordingSession(
                            id = id,
                            name = name.ifBlank { id.take(8) },
                            startEpochMs = start,
                            endEpochMs = end,
                            samplingMs = samplingMs,
                            samples = samples,
                            targetAppLabel = targetAppLabel,
                            targetAppPackage = targetAppPackage,
                            metricKeys = metricKeys,
                        ),
                    )
                }
            }

            sessions.clear()
            sessions.addAll(restored)
        } catch (_: Throwable) {
            // ignore corrupt file
        }
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun addSession(appContext: Context?, session: RecordingSession, retainLimit: Int) {
        sessions.add(0, session)
        trim(retainLimit)
        if (appContext != null) persistAsync(appContext)
    }

    fun trim(retainLimit: Int) {
        val limit = retainLimit.coerceIn(1, 500)
        while (sessions.size > limit) {
            sessions.removeAt(sessions.lastIndex)
        }
    }

    fun findById(id: String): RecordingSession? {
        return sessions.firstOrNull { it.id == id }
    }

    fun persistAsync(appContext: Context) {
        Thread {
            persistBlocking(appContext)
        }.start()
    }

    private fun persistBlocking(appContext: Context) {
        synchronized(ioLock) {
            try {
                val arr = JSONArray()
                sessions.forEach { s ->
                    val obj = JSONObject()
                    obj.put("id", s.id)
                    obj.put("name", s.name)
                    obj.put("startEpochMs", s.startEpochMs)
                    obj.put("endEpochMs", s.endEpochMs)
                    obj.put("samplingMs", s.samplingMs)
                    if (!s.targetAppLabel.isNullOrBlank()) obj.put("targetAppLabel", s.targetAppLabel)
                    if (!s.targetAppPackage.isNullOrBlank()) obj.put("targetAppPackage", s.targetAppPackage)

                    val mk = JSONArray()
                    s.metricKeys.forEach { mk.put(it) }
                    obj.put("metricKeys", mk)

                    val samplesArr = JSONArray()
                    s.samples.forEach { smp ->
                        val sObj = JSONObject()
                        sObj.put("t", smp.timestampEpochMs)
                        val vObj = JSONObject()
                        smp.values.forEach { (k, v) ->
                            vObj.put(k, v)
                        }
                        sObj.put("v", vObj)
                        samplesArr.put(sObj)
                    }
                    obj.put("samples", samplesArr)
                    arr.put(obj)
                }

                val file = File(appContext.filesDir, FILE_NAME)
                file.writeText(arr.toString())
            } catch (_: Throwable) {
            }
        }
    }
}
