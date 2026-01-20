package com.example.split_table_ai

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.OkHttpClient
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.http.GET // <-- Reverted
import retrofit2.http.Path // <-- Reverted
import okhttp3.ResponseBody


// --- No POST data classes needed ---


// --- Retrofit API Interface ---
interface PollinationApiService {

    // --- Reverted to the original GET implementation ---
    @GET("{prompt}")
    suspend fun generateText(
        @Path("prompt") prompt: String,
        @Query("json") json: Boolean = true
    ): ResponseBody
}


// --- Singleton object to create and hold the Retrofit instance ---
object PollinationApiClient {

    private val json = Json {
        ignoreUnknownKeys = true // Be safe if API adds new fields
    }

    // Increase timeouts for potentially long AI responses
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()

    val api: PollinationApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://text.pollinations.ai/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PollinationApiService::class.java)
    }
}