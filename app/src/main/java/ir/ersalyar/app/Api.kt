package ir.ersalyar.app

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

data class AuthRequest(val mobile: String, val password: String, val name: String? = null)
data class AuthResponse(val token: String, @SerializedName("user_id") val userId: Int)
data class MeResponse(val user: User?, val subscription: Subscription?, @SerializedName("subscription_active") val subscriptionActive: Boolean = false, @SerializedName("plan_daily_slots") val planDailySlots: Int = 0, @SerializedName("daily_slots_used") val dailySlotsUsed: Int = 0, @SerializedName("daily_slots_remaining") val dailySlotsRemaining: Int = 0)
data class User(val id: Int, val name: String, val mobile: String, val role: String, val status: String)
data class Subscription(@SerializedName("plan_type") val planType: String, @SerializedName("start_date") val startDate: String, @SerializedName("end_date") val endDate: String, val status: String)
data class Schedule(val id: Int, val content: String, @SerializedName("image_uri") val imageUri: String? = null, @SerializedName("image_uris") val imageUris: List<String> = emptyList(), val channel: String, @SerializedName("group_name") val groupName: String, @SerializedName("group_names") val groupNames: List<String> = emptyList(), @SerializedName("group_count") val groupCount: Int = 0, @SerializedName("recipient_names") val recipientNames: List<String> = emptyList(), @SerializedName("recipient_count") val recipientCount: Int = 0, @SerializedName("schedule_type") val scheduleType: String, @SerializedName("schedule_time") val scheduleTime: String, val weekdays: String, val status: String)
data class ScheduleRequest(val content: String, val channel: String, @SerializedName("group_names") val groupNames: List<String>, @SerializedName("schedule_type") val scheduleType: String, @SerializedName("schedule_time") val scheduleTime: String, val weekdays: String = "", val status: String = "active", @SerializedName("recipient_names") val recipientNames: List<String> = emptyList())
data class ScheduleList(@SerializedName("items") val items: List<Schedule>)
data class GroupItem(val id: Int, val name: String, val channel: String, @SerializedName("created_at") val createdAt: String)
data class GroupList(@SerializedName("items") val items: List<GroupItem>)
data class GroupRequest(val name: String, val channel: String)
data class RecipientItem(val id: Int, val name: String, val channel: String, @SerializedName("target_key") val targetKey: String = "", @SerializedName("created_at") val createdAt: String = "")
data class RecipientList(@SerializedName("items") val items: List<RecipientItem>)
data class RecipientRequest(val name: String, val channel: String, @SerializedName("target_key") val targetKey: String = "")
data class RecipientListItem(val id: Int, val name: String, val channel: String, @SerializedName("recipient_count") val recipientCount: Int = 0, @SerializedName("created_at") val createdAt: String = "")
data class RecipientListResponse(@SerializedName("items") val items: List<RecipientListItem>)
data class RecipientListDetail(val id: Int, val name: String, val channel: String, @SerializedName("recipient_count") val recipientCount: Int = 0)
data class RecipientListDetailResponse(val list: RecipientListDetail, val recipients: List<RecipientItem>)
data class RecipientListRequest(val name: String, val channel: String, @SerializedName("recipient_ids") val recipientIds: List<Int> = emptyList())
data class DirectMessageRequest(val content: String, val channel: String, @SerializedName("recipient_ids") val recipientIds: List<Int>)
data class DirectMessageResponse(val ok: Boolean, val channel: String, val content: String, val recipients: List<RecipientItem>)
data class BatchRequest(@SerializedName("contents") val contents: List<String>, val channel: String, @SerializedName("group_names") val groupNames: List<String>, @SerializedName("schedule_type") val scheduleType: String, @SerializedName("schedule_time") val scheduleTime: String, @SerializedName("interval_seconds") val intervalSeconds: Int = 0, val weekdays: String = "", @SerializedName("recipient_names") val recipientNames: List<String> = emptyList(), @SerializedName("image_uris_per_content") val imageUrisPerContent: List<List<String>> = emptyList())
data class BatchResponse(val ids: List<Int>, @SerializedName("group_count") val groupCount: Int, @SerializedName("message_count") val messageCount: Int)
data class BatchContent(val id: Int, val content: String, @SerializedName("schedule_time") val scheduleTime: String, val status: String, @SerializedName("batch_order") val batchOrder: Int)
data class MessageBatch(val id: Int, val channel: String, @SerializedName("schedule_type") val scheduleType: String, @SerializedName("schedule_time") val scheduleTime: String, val weekdays: String, @SerializedName("interval_seconds") val intervalSeconds: Int, val status: String, @SerializedName("group_names") val groupNames: List<String>, @SerializedName("group_count") val groupCount: Int, @SerializedName("recipient_names") val recipientNames: List<String> = emptyList(), @SerializedName("recipient_count") val recipientCount: Int = 0, val contents: List<BatchContent>)
data class BatchList(@SerializedName("items") val items: List<MessageBatch>)
data class PaymentSettings(@SerializedName("card_number") val cardNumber: String, @SerializedName("account_holder") val accountHolder: String, @SerializedName("monthly_price") val monthlyPrice: Long, @SerializedName("yearly_price") val yearlyPrice: Long, @SerializedName("trial_days") val trialDays: Int, @SerializedName("support_phone") val supportPhone: String = "09372544666")
data class PaymentSettingsResponse(val settings: PaymentSettings?)
data class DiscountValidationRequest(@SerializedName("plan_type") val planType: String, @SerializedName("coupon_code") val couponCode: String)
data class DiscountValidationResponse(@SerializedName("final_amount") val finalAmount: Long, @SerializedName("discount_amount") val discountAmount: Long, val percent: Int)
data class PaymentSubmitRequest(@SerializedName("plan_type") val planType: String, @SerializedName("payer_reference") val payerReference: String, @SerializedName("receipt_name") val receiptName: String? = null, @SerializedName("coupon_code") val couponCode: String? = null)
data class PaymentResponse(@SerializedName("payment_id") val paymentId: Int, val status: String, val amount: Long? = null, val message: String?)
data class PaymentItem(val id: Int, @SerializedName("plan_type") val planType: String, val amount: Long, @SerializedName("payer_reference") val payerReference: String, @SerializedName("receipt_name") val receiptName: String?, val status: String, @SerializedName("admin_note") val adminNote: String?, @SerializedName("created_at") val createdAt: String)
data class PaymentList(@SerializedName("items") val items: List<PaymentItem>)
data class ServerSendLog(@SerializedName("id") val id: Long, @SerializedName("message_id") val messageId: Int?, val channel: String, val target: String, val content: String = "", val status: String, @SerializedName("error_message") val errorMessage: String?, @SerializedName("sent_at") val sentAt: String?)
data class ServerSendLogList(@SerializedName("items") val items: List<ServerSendLog>)
data class UploadSendLogItem(@SerializedName("local_id") val localId: Long, @SerializedName("message_id") val messageId: Int?, val channel: String, val target: String, val content: String, val success: Boolean, val detail: String, @SerializedName("sent_at") val sentAt: String)
data class UploadSendLogsRequest(val items: List<UploadSendLogItem>)
data class SupportMessage(val id: Int, @SerializedName("sender_role") val senderRole: String, val content: String, @SerializedName("created_at") val createdAt: String)
data class SupportThread(val id: Int, @SerializedName("user_id") val userId: Int, val status: String, @SerializedName("updated_at") val updatedAt: String)
data class SupportThreadResponse(val thread: SupportThread, @SerializedName("items") val items: List<SupportMessage>)
data class SupportMessageRequest(val content: String)


interface ErsalyarApi {
    @POST("api/auth/register") suspend fun register(@Body request: AuthRequest): AuthResponse
    @POST("api/auth/login") suspend fun login(@Body request: AuthRequest): AuthResponse
    @POST("api/auth/logout") suspend fun logout(@Header("Authorization") bearer: String): Map<String, Any>
    @GET("api/me") suspend fun me(@Header("Authorization") bearer: String): MeResponse
    @GET("api/schedules") suspend fun schedules(@Header("Authorization") bearer: String): ScheduleList
    @PUT("api/schedules/{id}") suspend fun updateSchedule(@Header("Authorization") bearer: String, @Path("id") id: Int, @Body request: ScheduleRequest): Map<String, Any>
    @POST("api/schedules/{id}/status") suspend fun setScheduleStatus(@Header("Authorization") bearer: String, @Path("id") id: Int, @Body body: Map<String, String>): Map<String, Any>
    @DELETE("api/schedules/{id}") suspend fun deleteSchedule(@Header("Authorization") bearer: String, @Path("id") id: Int): Map<String, Any>
    @POST("api/schedules") suspend fun createSchedule(@Header("Authorization") bearer: String, @Body request: ScheduleRequest): Map<String, Any>
    @POST("api/message-batches") suspend fun createBatch(@Header("Authorization") bearer: String, @Body request: BatchRequest): BatchResponse
    @GET("api/message-batches") suspend fun messageBatches(@Header("Authorization") bearer: String): BatchList
    @PUT("api/message-batches/{id}") suspend fun updateBatch(@Header("Authorization") bearer: String, @Path("id") id: Int, @Body request: BatchRequest): Map<String, Any>
    @POST("api/message-batches/{id}/status") suspend fun setBatchStatus(@Header("Authorization") bearer: String, @Path("id") id: Int, @Body body: Map<String, String>): Map<String, Any>
    @DELETE("api/message-batches/{id}") suspend fun deleteBatch(@Header("Authorization") bearer: String, @Path("id") id: Int): Map<String, Any>
    @GET("api/groups") suspend fun groups(@Header("Authorization") bearer: String): GroupList
    @POST("api/groups") suspend fun addGroup(@Header("Authorization") bearer: String, @Body request: GroupRequest): GroupItem
    @DELETE("api/groups/{id}") suspend fun deleteGroup(@Header("Authorization") bearer: String, @Path("id") id: Int): Map<String, Any>
    @GET("api/recipients") suspend fun recipients(@Header("Authorization") bearer: String): RecipientList
    @POST("api/recipients") suspend fun addRecipient(@Header("Authorization") bearer: String, @Body request: RecipientRequest): RecipientItem
    @DELETE("api/recipients/{id}") suspend fun deleteRecipient(@Header("Authorization") bearer: String, @Path("id") id: Int): Map<String, Any>
    @GET("api/recipient-lists") suspend fun recipientLists(@Header("Authorization") bearer: String): RecipientListResponse
    @POST("api/recipient-lists") suspend fun addRecipientList(@Header("Authorization") bearer: String, @Body request: RecipientListRequest): RecipientListItem
    @GET("api/recipient-lists/{id}") suspend fun recipientList(@Header("Authorization") bearer: String, @Path("id") id: Int): RecipientListDetailResponse
    @PUT("api/recipient-lists/{id}") suspend fun updateRecipientList(@Header("Authorization") bearer: String, @Path("id") id: Int, @Body request: RecipientListRequest): RecipientListDetailResponse
    @DELETE("api/recipient-lists/{id}") suspend fun deleteRecipientList(@Header("Authorization") bearer: String, @Path("id") id: Int): Map<String, Any>
    @POST("api/direct-messages") suspend fun directMessage(@Header("Authorization") bearer: String, @Body request: DirectMessageRequest): DirectMessageResponse
    @GET("api/payment-settings") suspend fun paymentSettings(@Header("Authorization") bearer: String): PaymentSettingsResponse
    @POST("api/discount-codes/validate") suspend fun validateDiscount(@Header("Authorization") bearer: String, @Body request: DiscountValidationRequest): DiscountValidationResponse
    @POST("api/payments/submit") suspend fun submitPayment(@Header("Authorization") bearer: String, @Body request: PaymentSubmitRequest): PaymentResponse
    @GET("api/payments") suspend fun payments(@Header("Authorization") bearer: String): PaymentList
    @POST("api/reports/send-logs") suspend fun uploadSendLogs(@Header("Authorization") bearer: String, @Body request: UploadSendLogsRequest): Map<String,Any>
    @GET("api/reports/send-logs") suspend fun serverSendLogs(@Header("Authorization") bearer: String): ServerSendLogList
    @DELETE("api/reports/send-logs") suspend fun clearServerSendLogs(@Header("Authorization") bearer: String): Map<String,Any>
    @GET("api/support/thread") suspend fun supportThread(@Header("Authorization") bearer: String): SupportThreadResponse
    @POST("api/support/messages") suspend fun supportMessage(@Header("Authorization") bearer: String, @Body request: SupportMessageRequest): Map<String,Any>
}
