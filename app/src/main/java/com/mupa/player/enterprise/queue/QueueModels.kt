package com.mupa.player.enterprise.queue

import org.json.JSONObject

/**
 * Modelos do MUPA Queue no lado do player.
 *
 * Espelham o `CONTRATO_API.md` — nenhuma inferência sobre o formato: o que não está no contrato
 * não é lido aqui. O parsing é tolerante a campo ausente porque a Edge Function evolui antes do
 * player em campo, mas nunca inventa default para o que muda comportamento (ver [QueueKeymap]).
 */

/** Papel do dispositivo no setor, conforme `queue_device_bindings.role`. */
enum class QueueRole {
    CALLER,
    DISPLAY,
    ISSUER,
    UNKNOWN,
    ;

    companion object {
        fun parse(raw: String?): QueueRole = when (raw?.trim()?.uppercase()) {
            "CALLER" -> CALLER
            "DISPLAY" -> DISPLAY
            "ISSUER" -> ISSUER
            else -> UNKNOWN
        }
    }
}

/** Ação de fila disparada pelo controle remoto. Nomes iguais aos segmentos de rota. */
enum class QueueAction(val route: String) {
    PLAY("repeat"),
    NEXT("next"),
    BACK("back"),
}

/**
 * Mapeamento tecla física → ação.
 *
 * **Não existe default em código.** O contrato é explícito: `remote_keymap` vazio significa que o
 * aparelho ainda não teve as teclas capturadas, e o player deve entrar em modo de aprendizado. Um
 * default embutido faria um X96 mal configurado chamar senha com a tecla errada — pior que não
 * responder, porque o erro só apareceria com o cliente na frente do balcão.
 */
data class QueueKeymap(
    val play: Int? = null,
    val next: Int? = null,
    val back: Int? = null,
) {
    val isComplete: Boolean get() = play != null && next != null && back != null

    val isEmpty: Boolean get() = play == null && next == null && back == null

    fun actionFor(keyCode: Int): QueueAction? = when (keyCode) {
        play -> QueueAction.PLAY
        next -> QueueAction.NEXT
        back -> QueueAction.BACK
        else -> null
    }

    fun keyCodeFor(action: QueueAction): Int? = when (action) {
        QueueAction.PLAY -> play
        QueueAction.NEXT -> next
        QueueAction.BACK -> back
    }

    fun with(action: QueueAction, keyCode: Int): QueueKeymap = when (action) {
        QueueAction.PLAY -> copy(play = keyCode)
        QueueAction.NEXT -> copy(next = keyCode)
        QueueAction.BACK -> copy(back = keyCode)
    }

    /** Uma tecla não pode executar duas ações — a captura precisa saber disso para avisar. */
    fun conflictFor(keyCode: Int, target: QueueAction): QueueAction? =
        QueueAction.values().firstOrNull { it != target && keyCodeFor(it) == keyCode }

    companion object {
        fun fromJson(obj: JSONObject?): QueueKeymap {
            if (obj == null) return QueueKeymap()
            fun key(name: String): Int? = obj.optInt(name, -1).takeIf { it > 0 }
            return QueueKeymap(play = key("play"), next = key("next"), back = key("back"))
        }
    }
}

/** Resposta de `GET /config`. */
data class QueueDeviceConfig(
    val sectorId: String,
    val sectorName: String,
    val role: QueueRole,
    val prefix: String,
    val isActive: Boolean,
    val overlayDurationMs: Long,
    val audioEnabled: Boolean,
    val keymap: QueueKeymap,
    val serviceDate: String,
) {
    /** Só quem tem vínculo `CALLER` ativo responde a tecla — mitigação 1 de ARQUITETURA §8.1. */
    val canCall: Boolean get() = role == QueueRole.CALLER && isActive

    fun toJson(): String = JSONObject()
        .put("sector_id", sectorId)
        .put("sector_name", sectorName)
        .put("role", role.name)
        .put("prefix", prefix)
        .put("is_active", isActive)
        .put("overlay_duration_ms", overlayDurationMs)
        .put("audio_enabled", audioEnabled)
        .put("service_date", serviceDate)
        .put(
            "remote_keymap",
            JSONObject().apply {
                keymap.play?.let { put("play", it) }
                keymap.next?.let { put("next", it) }
                keymap.back?.let { put("back", it) }
            },
        )
        .toString()

    companion object {
        fun fromJson(obj: JSONObject): QueueDeviceConfig? {
            val sectorId = obj.optString("sector_id").takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            return QueueDeviceConfig(
                sectorId = sectorId,
                sectorName = obj.optString("sector_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                role = QueueRole.parse(obj.optString("role")),
                prefix = obj.optString("prefix").takeIf { it != "null" }.orEmpty(),
                isActive = obj.optBoolean("is_active", false),
                overlayDurationMs = obj.optLong("overlay_duration_ms", DEFAULT_OVERLAY_MS)
                    .coerceIn(MIN_OVERLAY_MS, MAX_OVERLAY_MS),
                audioEnabled = obj.optBoolean("audio_enabled", true),
                keymap = QueueKeymap.fromJson(obj.optJSONObject("remote_keymap")),
                serviceDate = obj.optString("service_date").takeIf { it != "null" }.orEmpty(),
            )
        }

        /**
         * Tempo que a tela da chamada fica no ar quando o servidor não disser outro.
         *
         * 5 s por decisão do Antunes em 2026-08-17: a tela **cobre** o conteúdo, então cada segundo
         * a mais é publicidade fora do ar. O valor real vem de `queue_sector_config
         * .overlay_duration_ms` — cuja default no banco ainda é 15 s, e precisa ser ajustada por
         * setor no seed.
         */
        const val DEFAULT_OVERLAY_MS = 5_000L
        private const val MIN_OVERLAY_MS = 2_000L
        private const val MAX_OVERLAY_MS = 120_000L
    }
}

/** Uma entrada do log de chamadas (`queue_calls`), como devolvida por `GET /queue`. */
data class QueueCall(
    val ticketId: String,
    val number: String,
    val type: String,
    val status: String?,
    val position: Int,
    val calledAt: String?,
    val repeatCount: Int,
) {
    val isPriority: Boolean
        get() = type.equals("PREFERENCIAL", ignoreCase = true) ||
            type.equals("PRIORIDADE", ignoreCase = true)

    companion object {
        fun fromJson(obj: JSONObject): QueueCall? {
            val number = obj.optString("number").takeIf { it.isNotBlank() && it != "null" } ?: return null
            return QueueCall(
                ticketId = obj.optString("ticket_id").orEmpty(),
                number = number,
                type = obj.optString("type").takeIf { it != "null" }.orEmpty().ifBlank { "NORMAL" },
                status = obj.optString("status").takeIf { it.isNotBlank() && it != "null" },
                position = obj.optInt("position", 0),
                calledAt = obj.optString("called_at").takeIf { it.isNotBlank() && it != "null" },
                repeatCount = obj.optInt("repeat_count", 0),
            )
        }
    }
}

/** Resposta de `GET /queue`. */
data class QueueState(
    val sectorId: String,
    val serviceDate: String,
    val cursorPosition: Int?,
    val current: QueueCall?,
    val history: List<QueueCall>,
    val waitingCount: Int,
) {
    /** Histórico sem a senha atual — é o que o rodapé mostra como "últimas chamadas". */
    val previousCalls: List<QueueCall>
        get() = history.filter { it.position != current?.position }

    companion object {
        /**
         * Estado sintético para o gatilho de verificação em hardware — nunca vem do servidor.
         *
         * Existe porque o painel de últimas chamadas só é avaliável com dados: uma coluna vazia
         * esconde erro de alinhamento, de tamanho de fonte e de quantidade de linhas.
         */
        fun forPreview(current: String, previous: List<String>, waitingCount: Int): QueueState {
            val currentPosition = previous.size + 1
            fun call(number: String, position: Int) = QueueCall(
                ticketId = "preview-$position",
                number = number,
                type = "NORMAL",
                status = "CALLING",
                position = position,
                calledAt = null,
                repeatCount = 0,
            )
            return QueueState(
                sectorId = "preview",
                serviceDate = "",
                cursorPosition = currentPosition,
                current = call(current, currentPosition),
                history = listOf(call(current, currentPosition)) +
                    previous.mapIndexed { i, n -> call(n, currentPosition - 1 - i) },
                waitingCount = waitingCount,
            )
        }

        fun fromJson(obj: JSONObject): QueueState {
            val historyArr = obj.optJSONArray("history")
            val history = buildList {
                for (i in 0 until (historyArr?.length() ?: 0)) {
                    historyArr?.optJSONObject(i)?.let { QueueCall.fromJson(it) }?.let { add(it) }
                }
            }
            return QueueState(
                sectorId = obj.optString("sector_id").orEmpty(),
                serviceDate = obj.optString("service_date").orEmpty(),
                cursorPosition = obj.optInt("cursor_position", -1).takeIf { it >= 0 },
                current = obj.optJSONObject("current")?.let { QueueCall.fromJson(it) },
                history = history,
                waitingCount = obj.optInt("waiting_count", 0),
            )
        }
    }
}

/**
 * Resultado de `POST /queue/{next|back|repeat}`.
 *
 * `Empty`, `AtStart` e `NoCurrent` **não são erro** (CONTRATO §3): são estados terminais normais da
 * operação e a TV precisa dar retorno visual ao atendente em vez de ficar muda.
 */
sealed class QueueCallOutcome {
    data class Called(
        val ticketId: String,
        val number: String,
        val type: String,
        val position: Int,
        val reopened: Boolean,
    ) : QueueCallOutcome()

    /**
     * Rechamada da senha atual (`repeat`).
     *
     * **Divergência conhecida:** o `CONTRATO_API.md` §3 documenta só `called` como sucesso, mas
     * `queue_call_repeat` devolve `status = "repeated"` (`20260816120200_queue_engine.sql:689`).
     * Tratar os dois é o certo de qualquer forma — a rechamada não move o cursor e merece
     * apresentação própria na TV (pulso, não reentrada do card) —, mas o contrato precisa ser
     * corrigido com a Duda antes que o totem e o terminal implementem contra ele.
     */
    data class Repeated(
        val ticketId: String,
        val number: String,
        val type: String,
        val position: Int,
        val repeatCount: Int,
    ) : QueueCallOutcome()

    /** `next` sem senha em espera. */
    object Empty : QueueCallOutcome()

    /** `back` já no início do dia. */
    object AtStart : QueueCallOutcome()

    /** `repeat` sem nada chamado ainda. */
    object NoCurrent : QueueCallOutcome()

    data class Unknown(val status: String) : QueueCallOutcome()

    companion object {
        fun fromJson(obj: JSONObject): QueueCallOutcome {
            return when (val status = obj.optString("status").trim()) {
                "called" -> Called(
                    ticketId = obj.optString("ticket_id").orEmpty(),
                    number = obj.optString("number").orEmpty(),
                    type = obj.optString("type").takeIf { it != "null" }.orEmpty().ifBlank { "NORMAL" },
                    position = obj.optInt("position", 0),
                    reopened = obj.optBoolean("reopened", false),
                )

                "repeated" -> Repeated(
                    ticketId = obj.optString("ticket_id").orEmpty(),
                    number = obj.optString("number").orEmpty(),
                    type = obj.optString("type").takeIf { it != "null" }.orEmpty().ifBlank { "NORMAL" },
                    position = obj.optInt("position", 0),
                    repeatCount = obj.optInt("repeat_count", 0),
                )

                "empty" -> Empty
                "at_start" -> AtStart
                "no_current" -> NoCurrent
                else -> Unknown(status)
            }
        }
    }
}
