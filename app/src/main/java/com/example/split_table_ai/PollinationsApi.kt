package com.example.split_table_ai

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface PollinationsApi {
    // GET endpoint : https://text.pollinations.ai/{prompt}?json=true
    @GET("{prompt}")
    suspend fun generateText(
        @Path(value = "prompt", encoded = true) prompt: String,
        @Query("json") json: Boolean = true
    ): Response<String>

    companion object {
        private const val BASE_URL = "https://text.pollinations.ai/"

        fun create(): PollinationsApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build()
            return retrofit.create(PollinationsApi::class.java)
        }

        fun encodePrompt(prompt: String): String =
            URLEncoder.encode(prompt, StandardCharsets.UTF_8.toString())
    }
}


