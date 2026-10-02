package com.mupa.player.enterprise.network

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

interface SupabaseApi {
    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST
    suspend fun postJson(
        @Url url: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): ResponseBody

    @GET
    suspend fun getCompaniesByCode(
        @Url url: String,
        @Query("select") select: String = "*",
        @Query("code") codeEq: String,
    ): ResponseBody

    /** GET livre em PostgREST — a URL já vem com os filtros montados. */
    @Headers("Accept: application/json")
    @GET
    suspend fun getRaw(@Url url: String): ResponseBody

    /** PATCH em PostgREST (`?id=eq.{id}`). `return=minimal` evita devolver a linha alterada. */
    @Headers("Content-Type: application/json", "Accept: application/json", "Prefer: return=minimal")
    @PATCH
    suspend fun patchJson(
        @Url url: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): ResponseBody

    /** INSERT em PostgREST. */
    @Headers("Content-Type: application/json", "Accept: application/json", "Prefer: return=minimal")
    @POST
    suspend fun insertJson(
        @Url url: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): ResponseBody

    /** UPSERT em PostgREST — a URL deve incluir `?on_conflict=<coluna_unica>`. */
    @Headers("Content-Type: application/json", "Accept: application/json", "Prefer: return=minimal,resolution=merge-duplicates")
    @POST
    suspend fun upsertJson(
        @Url url: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): ResponseBody
}
