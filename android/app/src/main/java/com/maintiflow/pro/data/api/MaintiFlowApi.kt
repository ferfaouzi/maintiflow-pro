package com.maintiflow.pro.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import kotlinx.serialization.json.JsonElement

interface MaintiFlowApi {
    // Authentification
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): TokenResponse
    @POST("auth/logout") suspend fun logout(): retrofit2.Response<Unit>
    @GET("auth/me") suspend fun me(): UserDto
    @POST("auth/change-password") suspend fun changePassword(@Body body: ChangePasswordRequest): retrofit2.Response<Unit>

    // Référentiels
    @GET("referentials") suspend fun referentials(): Referentials

    // Bons
    @GET("interventions")
    suspend fun search(
        @Query("q") q: String? = null,
        @Query("status") status: List<String>? = null,
        @Query("open_only") openOnly: Boolean? = null,
        @Query("mine") mine: Boolean? = null,
        @Query("line_id") lineId: Int? = null,
        @Query("machine_id") machineId: Int? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): TicketPage

    @POST("interventions") suspend fun create(@Body body: CreateTicketRequest): TicketDetail
    @GET("interventions/{id}") suspend fun get(@Path("id") id: String): TicketDetail
    @PATCH("interventions/{id}") suspend fun patch(@Path("id") id: String, @Body body: PatchTicketRequest): TicketDetail
    @POST("interventions/{id}/actions/{name}")
    suspend fun action(@Path("id") id: String, @Path("name") name: String, @Body body: ActionRequest): TicketDetail
    @POST("interventions/{id}/parts") suspend fun addPart(@Path("id") id: String, @Body body: PartRequest): TicketDetail
    @DELETE("interventions/{id}/parts/{partId}") suspend fun deletePart(@Path("id") id: String, @Path("partId") partId: Int): TicketDetail
    @GET("interventions/{id}/similar") suspend fun similar(@Path("id") id: String): SimilarResponse
    @Streaming @GET("interventions/{id}/pdf") suspend fun pdf(@Path("id") id: String): ResponseBody

    @Multipart
    @POST("interventions/{id}/attachments")
    suspend fun upload(@Path("id") id: String, @Part("kind") kind: RequestBody, @Part file: MultipartBody.Part): TicketDetail

    // Administration
    @GET("admin/settings") suspend fun settings(): SettingsResponse
    @PUT("admin/settings") suspend fun putSettings(@Body body: Map<String, JsonElement>): SettingsResponse
    @GET("admin/users") suspend fun users(): List<UserDto>
    @POST("admin/users") suspend fun createUser(@Body body: UserCreateRequest): UserDto
    @PATCH("admin/users/{id}") suspend fun updateUser(@Path("id") id: Int, @Body body: UserUpdateRequest): UserDto
    @GET("admin/lines") suspend fun lines(): List<LineDto>
    @POST("admin/lines") suspend fun createLine(@Body body: LineRequest): LineDto
    @GET("admin/machines") suspend fun machines(): List<MachineDto>
    @POST("admin/machines") suspend fun createMachine(@Body body: MachineRequest): MachineDto
    @GET("admin/categories") suspend fun categories(): List<CategoryDto>
    @POST("admin/categories") suspend fun createCategory(@Body body: CategoryRequest): CategoryDto
}
