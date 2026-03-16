package com.uber.autoaccept.supabase

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SupabaseException(val code: Int, message: String) : Exception("Supabase error $code: $message")

/**
 * Supabase REST API HTTP 클라이언트.
 * Auth API (/auth/v1) 및 PostgREST (/rest/v1) 를 HttpURLConnection 으로 직접 호출.
 */
object SupabaseClient {
    private const val TAG = "SupabaseClient"
    private const val TIMEOUT_MS = 15_000
    val gson = Gson()

    /** Supabase Auth 엔드포인트 POST */
    suspend fun authPost(path: String, body: Map<String, Any>, token: String? = null): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val url = URL("${SupabaseConfig.SUPABASE_URL}/auth/v1$path")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(gson.toJson(body)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw SupabaseException(code, response)

                val type = object : TypeToken<Map<String, Any?>>() {}.type
                gson.fromJson(response, type) ?: emptyMap()
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Supabase PostgREST 테이블 GET (행 조회) */
    suspend fun restGet(table: String, query: String = "", token: String): List<Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            val url = URL("${SupabaseConfig.SUPABASE_URL}/rest/v1/$table?$query")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw SupabaseException(code, response)

                val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
                gson.fromJson(response, type) ?: emptyList()
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Supabase PostgREST 테이블 POST (행 삽입, 단일 또는 배열) */
    suspend fun restPost(table: String, body: Any, token: String) {
        withContext(Dispatchers.IO) {
            val url = URL("${SupabaseConfig.SUPABASE_URL}/rest/v1/$table")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(gson.toJson(body)) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val stream = conn.errorStream ?: conn.inputStream
                    val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    throw SupabaseException(code, response)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Supabase PostgREST RPC 함수 호출 (anon 키로 호출, security definer 함수용) */
    suspend fun rpcPost(function: String, body: Map<String, Any>): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val url = URL("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/$function")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(gson.toJson(body)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw SupabaseException(code, response)

                val type = object : TypeToken<Map<String, Any?>>() {}.type
                gson.fromJson(response, type) ?: emptyMap()
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Supabase PostgREST UPSERT (onConflict 컬럼 기준 충돌 시 병합) */
    suspend fun restUpsert(table: String, body: Any, token: String, onConflict: String? = null) {
        withContext(Dispatchers.IO) {
            val query = if (onConflict != null) "?on_conflict=$onConflict" else ""
            val url = URL("${SupabaseConfig.SUPABASE_URL}/rest/v1/$table$query")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(gson.toJson(body)) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val stream = conn.errorStream ?: conn.inputStream
                    val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    throw SupabaseException(code, response)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Supabase 연결 테스트 (uber_users 테이블 접근 확인) */
    suspend fun testConnection(token: String): Boolean {
        return try {
            restGet("uber_users", "select=device_id&limit=1", token)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed: ${e.message}")
            false
        }
    }
}
