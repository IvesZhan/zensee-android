package com.zensee.android.data

import android.content.Context
import com.zensee.android.AuthManager
import com.zensee.android.BuildConfig
import com.zensee.android.RawHttpResponse
import com.zensee.android.SessionAwareRequestExecutor
import com.zensee.android.domain.ZenStatsCalculator
import com.zensee.android.model.GroupCard
import com.zensee.android.model.HistoryDayGroup
import com.zensee.android.model.HomeSnapshot
import com.zensee.android.model.MeditationSessionSummary
import com.zensee.android.model.MoodRecord
import com.zensee.android.model.MoodDayGroup
import com.zensee.android.model.ProfileSnapshot
import com.zensee.android.model.ZenQuote
import com.zensee.android.model.ZenStatsSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.concurrent.thread

object ZenRepository {
    private const val PREFS_NAME = "zensee_android"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_MOODS = "moods"

    private lateinit var appContext: Context

    private val quotes = listOf(
        ZenQuote("一切有为法，如梦幻泡影。", "金刚经"),
        ZenQuote("应无所住，而生其心。", "金刚经"),
        ZenQuote("色即是空，空即是色。", "心经"),
        ZenQuote("菩提本无树，明镜亦非台。", "六祖坛经"),
        ZenQuote("天地与我并生，万物与我为一。", "庄子")
    )

    private val groups = listOf(
        GroupCard("1", "清晨禅修社", "每天清晨留出二十分钟安住呼吸，分享当日的禅修时刻。", 12, true),
        GroupCard("2", "夜坐静心会", "适合下班后收束一天的杂念，在夜色里回到自己。", 8, true),
        GroupCard("3", "周末山居计划", "周末长坐与复盘互相督促，建立稳定修行节律。", 21, false)
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AuthManager.initialize(context)
        if (!AuthManager.state().isAuthenticated && loadSessions().isEmpty()) {
            seedPreviewData()
        }
    }

    fun refreshRemoteData(): Boolean {
        val authState = AuthManager.state()
        val accessToken = AuthManager.accessTokenOrNull()
        val userId = authState.userId
        if (!authState.isAuthenticated || accessToken.isNullOrBlank() || userId.isNullOrBlank()) {
            return false
        }

        val sessions = CloudSyncApi.fetchSessions(userId, accessToken)
        val moods = CloudSyncApi.fetchMoods(userId, accessToken)
        persistSessions(sessions)
        persistMoods(moods)
        return true
    }

    fun getHomeSnapshot(today: LocalDate = LocalDate.now()): HomeSnapshot {
        if (!AuthManager.state().isAuthenticated) {
            return HomeSnapshot(
                todayMinutes = 0,
                streakDays = 0,
                totalDays = 0,
                currentMood = "平稳",
                quote = quoteFor(today),
                recentMoods = emptyList()
            )
        }
        val stats = getStatsSnapshot(today)
        val moods = getMoodRecords()
            .sortedByDescending { it.createdAt }
            .take(6)
        val currentMood = moods.firstOrNull()?.mood ?: "平稳"
        return HomeSnapshot(
            todayMinutes = stats.heatmapByDate[today] ?: 0,
            streakDays = stats.streakDays,
            totalDays = stats.totalDays,
            currentMood = currentMood,
            quote = quoteFor(today),
            recentMoods = moods
        )
    }

    fun getStatsSnapshot(today: LocalDate = LocalDate.now()): ZenStatsSnapshot {
        val allSessions = loadSessions()
        val statsSessions = if (AuthManager.state().isAuthenticated) {
            val recentCutoff = today.minusDays(180)
            val yearStart = today.withDayOfYear(1)
            val queryStart = if (recentCutoff.isBefore(yearStart)) recentCutoff else yearStart
            allSessions.filter { !it.sessionDate.isBefore(queryStart) }
        } else {
            allSessions
        }
        return ZenStatsCalculator.calculate(statsSessions, today)
    }

    fun getGroups(): List<GroupCard> = groups

    fun getProfileSnapshot(today: LocalDate = LocalDate.now()): ProfileSnapshot {
        val auth = AuthManager.state()
        val stats = if (auth.isAuthenticated) getStatsSnapshot(today) else ZenStatsSnapshot(
            totalDays = 0,
            totalMinutes = 0,
            streakDays = 0,
            weeklyMinutes = emptyList(),
            weeklyAverageMinutes = 0,
            heatmapByDate = emptyMap(),
            yearHeatmapByDate = emptyMap()
        )
        return ProfileSnapshot(
            displayName = auth.displayName,
            email = if (auth.isAuthenticated) auth.email else "登录后同步资料",
            streakDays = stats.streakDays,
            totalDays = stats.totalDays,
            totalMinutes = stats.totalMinutes
        )
    }

    fun getRecentSessions(limit: Int = 7): List<MeditationSessionSummary> {
        return loadSessions()
            .sortedByDescending { it.startedAt }
            .take(limit)
    }

    fun getMeditationHistoryGroups(): List<HistoryDayGroup> {
        return loadSessions()
            .groupBy { it.sessionDate }
            .map { (date, sessions) ->
                HistoryDayGroup(
                    id = date.toString(),
                    date = date,
                    sessions = sessions.sortedBy { it.startedAt }
                )
            }
            .sortedByDescending { it.date }
    }

    fun getMoodRecords(limit: Int = 20): List<MoodRecord> {
        return loadMoods()
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    fun getMoodHistoryGroups(): List<MoodDayGroup> {
        return loadMoods()
            .groupBy { it.createdAt.atZone(zoneId()).toLocalDate() }
            .map { (date, records) ->
                MoodDayGroup(
                    id = date.toString(),
                    date = date,
                    records = records.sortedByDescending { it.createdAt }
                )
            }
            .sortedByDescending { it.date }
    }

    fun saveSession(
        durationMinutes: Int,
        startedAt: Instant,
        endedAt: Instant
    ) {
        val sessions = loadSessions().toMutableList()
        val session = MeditationSessionSummary(
            id = UUID.randomUUID().toString(),
            sessionDate = startedAt.atZone(zoneId()).toLocalDate(),
            durationMinutes = durationMinutes,
            startedAt = startedAt,
            endedAt = endedAt
        )
        sessions += session
        persistSessions(sessions)
        syncSessionIfNeeded(session)
    }

    fun saveMood(
        mood: String,
        note: String?,
        meditationDuration: Int?
    ) {
        val moods = loadMoods().toMutableList()
        val record = MoodRecord(
            id = UUID.randomUUID().toString(),
            mood = mood,
            note = note?.takeIf { it.isNotBlank() },
            meditationDuration = meditationDuration,
            createdAt = Instant.now()
        )
        moods += record
        persistMoods(moods)
        syncMoodIfNeeded(record)
    }

    private fun quoteFor(date: LocalDate): ZenQuote {
        val index = date.dayOfYear % quotes.size
        return quotes[index]
    }

    private fun seedPreviewData() {
        val today = LocalDate.now()
        val sessions = listOf(
            previewSession(today, 25, 7),
            previewSession(today.minusDays(1), 18, 6),
            previewSession(today.minusDays(2), 32, 5),
            previewSession(today.minusDays(5), 40, 4),
            previewSession(today.minusDays(8), 20, 6)
        )
        val moods = listOf(
            previewMood("平静", "收摄得还不错，杂念少了很多。", 25, 2),
            previewMood("专注", "开始前有些散乱，后半段逐渐安定。", 18, 1),
            previewMood("放松", "肩颈放松下来，呼吸也更深。", 32, 0)
        )
        persistSessions(sessions)
        persistMoods(moods)
    }

    private fun previewSession(date: LocalDate, minutes: Int, hour: Int): MeditationSessionSummary {
        val startedAt = date.atTime(hour, 0).atZone(zoneId()).toInstant()
        val endedAt = startedAt.plusSeconds((minutes * 60).toLong())
        return MeditationSessionSummary(
            id = UUID.randomUUID().toString(),
            sessionDate = date,
            durationMinutes = minutes,
            startedAt = startedAt,
            endedAt = endedAt
        )
    }

    private fun previewMood(mood: String, note: String, duration: Int, daysAgo: Long): MoodRecord {
        return MoodRecord(
            id = UUID.randomUUID().toString(),
            mood = mood,
            note = note,
            meditationDuration = duration,
            createdAt = Instant.now().minusSeconds(daysAgo * 86_400)
        )
    }

    private fun loadSessions(): List<MeditationSessionSummary> {
        val raw = prefs().getString(scopedKey(KEY_SESSIONS), "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                add(
                    MeditationSessionSummary(
                        id = obj.getString("id"),
                        sessionDate = LocalDate.parse(obj.getString("sessionDate")),
                        durationMinutes = obj.getInt("durationMinutes"),
                        startedAt = Instant.ofEpochMilli(obj.getLong("startedAt")),
                        endedAt = Instant.ofEpochMilli(obj.getLong("endedAt"))
                    )
                )
            }
        }
    }

    private fun loadMoods(): List<MoodRecord> {
        val raw = prefs().getString(scopedKey(KEY_MOODS), "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                add(
                    MoodRecord(
                        id = obj.getString("id"),
                        mood = obj.getString("mood"),
                        note = sanitizeNullableText(obj.opt("note")),
                        meditationDuration = obj.optInt("meditationDuration", -1).takeIf { it >= 0 },
                        createdAt = Instant.ofEpochMilli(obj.getLong("createdAt"))
                    )
                )
            }
        }
    }

    private fun persistSessions(sessions: List<MeditationSessionSummary>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("sessionDate", session.sessionDate.toString())
                    .put("durationMinutes", session.durationMinutes)
                    .put("startedAt", session.startedAt.toEpochMilli())
                    .put("endedAt", session.endedAt.toEpochMilli())
            )
        }
        prefs().edit().putString(scopedKey(KEY_SESSIONS), array.toString()).apply()
    }

    private fun persistMoods(moods: List<MoodRecord>) {
        val array = JSONArray()
        moods.forEach { mood ->
            array.put(
                JSONObject()
                    .put("id", mood.id)
                    .put("mood", mood.mood)
                    .put("note", mood.note ?: "")
                    .put("meditationDuration", mood.meditationDuration ?: -1)
                    .put("createdAt", mood.createdAt.toEpochMilli())
            )
        }
        prefs().edit().putString(scopedKey(KEY_MOODS), array.toString()).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun scopedKey(base: String): String = "${AuthManager.storageKeyPrefix()}_$base"

    private fun zoneId(): ZoneId = ZoneId.systemDefault()

    private fun sanitizeNullableText(value: Any?): String? {
        val text = when (value) {
            null, JSONObject.NULL -> return null
            else -> value.toString()
        }.trim()
        if (text.isBlank() || text.equals("null", ignoreCase = true)) return null
        return text
    }

    private fun syncSessionIfNeeded(session: MeditationSessionSummary) {
        val authState = AuthManager.state()
        val token = AuthManager.accessTokenOrNull()
        val userId = authState.userId
        if (!authState.isAuthenticated || token.isNullOrBlank() || userId.isNullOrBlank()) return

        thread(name = "zensee-sync-session") {
            runCatching {
                CloudSyncApi.insertSession(session, userId, token)
            }
        }
    }

    private fun syncMoodIfNeeded(record: MoodRecord) {
        val authState = AuthManager.state()
        val token = AuthManager.accessTokenOrNull()
        val userId = authState.userId
        if (!authState.isAuthenticated || token.isNullOrBlank() || userId.isNullOrBlank()) return

        thread(name = "zensee-sync-mood") {
            runCatching {
                CloudSyncApi.insertMood(record, userId, token)
            }
        }
    }
}

private object CloudSyncApi {
    private val BASE_URL = BuildConfig.SUPABASE_URL
    private val API_KEY = BuildConfig.SUPABASE_ANON_KEY

    fun fetchSessions(userId: String, accessToken: String): List<MeditationSessionSummary> {
        val body = request(
            path = "/rest/v1/meditation_sessions" +
                "?user_id=${encodedEq(userId)}" +
                "&select=id,user_id,session_date,duration_minutes,started_at,ended_at,created_at" +
                "&order=started_at.desc" +
                "&limit=365",
            method = "GET",
            accessToken = accessToken
        )
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val startedAt = parseInstant(obj.optString("started_at"))
                    ?: parseInstant(obj.optString("created_at"))
                    ?: Instant.EPOCH
                val endedAt = parseInstant(obj.optString("ended_at")) ?: startedAt
                add(
                    MeditationSessionSummary(
                        id = obj.getString("id"),
                        sessionDate = LocalDate.parse(obj.getString("session_date")),
                        durationMinutes = obj.getInt("duration_minutes"),
                        startedAt = startedAt,
                        endedAt = endedAt
                    )
                )
            }
        }
    }

    fun fetchMoods(userId: String, accessToken: String): List<MoodRecord> {
        val body = request(
            path = "/rest/v1/mood_records" +
                "?user_id=${encodedEq(userId)}" +
                "&select=id,user_id,mood,note,meditation_duration,created_at" +
                "&order=created_at.desc" +
                "&limit=30",
            method = "GET",
            accessToken = accessToken
        )
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                add(
                    MoodRecord(
                        id = obj.getString("id"),
                        mood = obj.getString("mood"),
                        note = sanitizeNullableText(obj.opt("note")),
                        meditationDuration = obj.optInt("meditation_duration", -1).takeIf { it >= 0 },
                        createdAt = parseInstant(obj.optString("created_at")) ?: Instant.EPOCH
                    )
                )
            }
        }
    }

    fun insertSession(
        session: MeditationSessionSummary,
        userId: String,
        accessToken: String
    ) {
        val payload = JSONObject()
            .put("id", session.id)
            .put("user_id", userId)
            .put("session_date", session.sessionDate.toString())
            .put("duration_minutes", session.durationMinutes)
            .put("started_at", session.startedAt.toString())
            .put("ended_at", session.endedAt.toString())
        request(
            path = "/rest/v1/meditation_sessions",
            method = "POST",
            body = payload,
            accessToken = accessToken
        )
    }

    fun insertMood(
        record: MoodRecord,
        userId: String,
        accessToken: String
    ) {
        val payload = JSONObject()
            .put("id", record.id)
            .put("user_id", userId)
            .put("mood", record.mood)
            .put("note", record.note)
            .put("meditation_duration", record.meditationDuration)
        request(
            path = "/rest/v1/mood_records",
            method = "POST",
            body = payload,
            accessToken = accessToken
        )
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String
    ): String {
        val response = SessionAwareRequestExecutor.execute(
            accessTokenProvider = { AuthManager.accessTokenOrNull() ?: accessToken },
            refreshSession = { AuthManager.refreshSessionIfPossible() },
            onSessionExpired = { }
        ) { token ->
            performRequest(
                path = path,
                method = method,
                body = body,
                accessToken = token
            )
        }
        if (response.code !in 200..299) {
            val message = SessionAwareRequestExecutor.parseErrorMessage(response.body)
                .ifBlank { "Cloud sync failed" }
            if (AuthManager.state().isAuthenticated) {
                throw IllegalStateException(message)
            }
            throw IllegalStateException("登录已过期，请重新登录")
        }
        return response.body
    }

    private fun performRequest(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String?
    ): RawHttpResponse {
        val token = accessToken ?: return RawHttpResponse(401, """{"message":"登录已过期，请重新登录"}""")
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            doInput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }

        if (body != null) {
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }.orEmpty()
        return RawHttpResponse(responseCode, responseText)
    }

    private fun encodedEq(value: String): String = URLEncoder.encode("eq.$value", "UTF-8")

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    private fun sanitizeNullableText(value: Any?): String? {
        val text = when (value) {
            null, JSONObject.NULL -> return null
            else -> value.toString()
        }.trim()
        if (text.isBlank() || text.equals("null", ignoreCase = true)) return null
        return text
    }
}
