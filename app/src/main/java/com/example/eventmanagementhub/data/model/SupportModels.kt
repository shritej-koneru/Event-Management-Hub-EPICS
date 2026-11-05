package com.example.eventmanagementhub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Postgres bigint ids may reach us as JSON numbers or strings depending on the
 * server's driver settings; accept both and keep them as strings in the app.
 */
object FlexIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        if (decoder is JsonDecoder) {
            val el = decoder.decodeJsonElement()
            if (el is JsonNull) "" else el.jsonPrimitive.content
        } else decoder.decodeString()

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Preview of the newest message in a ticket (Ticket.lastMessage). */
@Serializable
data class LastMessage(
    val body: String = "",
    val senderRole: String = "",
    val createdAt: String = ""
)

/** Contract 1.6.0 Ticket. */
@Serializable
data class SupportTicket(
    @Serializable(with = FlexIdSerializer::class) val id: String = "",
    val eventId: String = "",
    val eventTitle: String = "",
    val userId: String = "",
    val userName: String = "",
    val subject: String = "",
    /** "open" or "closed". */
    val status: String = "open",
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastMessageAt: String = "",
    val lastMessage: LastMessage? = null,
    val messageCount: Int = 0
)

/** Contract 1.6.0 Message. `clientId` is echoed so optimistic bubbles can be matched. */
@Serializable
data class SupportMessage(
    @Serializable(with = FlexIdSerializer::class) val id: String = "",
    @Serializable(with = FlexIdSerializer::class) val ticketId: String = "",
    val senderId: String? = null,
    val senderName: String = "",
    /** "user" or "manager". */
    val senderRole: String = "user",
    val body: String = "",
    val createdAt: String = "",
    val clientId: String? = null
)

@Serializable
data class TicketsResponse(val tickets: List<SupportTicket> = emptyList())

@Serializable
data class TicketMessagesResponse(
    val ticket: SupportTicket? = null,
    val messages: List<SupportMessage> = emptyList()
)

@Serializable
data class CreateTicketRequest(
    val eventId: String,
    val subject: String,
    val message: String,
    val clientId: String? = null
)

@Serializable
data class CreateTicketResponse(
    val ticket: SupportTicket? = null,
    val message: SupportMessage? = null
)

@Serializable
data class SendMessageRequest(val body: String, val clientId: String? = null)

@Serializable
data class SendMessageResponse(val message: SupportMessage? = null)
