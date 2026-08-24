package com.mupa.player.enterprise.queue

import android.graphics.Color
import org.json.JSONObject

/**
 * Identidade visual da fila, vinda de `queue_branding` pelo `GET /config`.
 *
 * É o mecanismo que a especificação §26 desenhou para "o mesmo produto ter identidades diferentes"
 * — azul e branco na farmácia, preto e vermelho no açougue — sem build nova por rede. A resolução
 * em cascata (loja → tenant) acontece no servidor; aqui só se lê o resultado.
 *
 * **Todo campo é opcional, e é isso que importa.** Uma loja que não configurou nada precisa
 * continuar com a tela legível, então cada cor ausente cai no valor do tema padrão em vez de virar
 * transparente ou preto. Configuração parcial é o caso normal, não a exceção.
 */
data class QueueBranding(
    val primaryColor: Int? = null,
    val accentColor: Int? = null,
    val backgroundColor: Int? = null,
    val headerBgColor: Int? = null,
    val headerTextColor: Int? = null,
    val logoUrl: String? = null,
    val footerText: String? = null,
    val preferentialLabel: String? = null,
) {
    val isEmpty: Boolean
        get() = primaryColor == null && accentColor == null && backgroundColor == null &&
            headerBgColor == null && headerTextColor == null &&
            logoUrl.isNullOrBlank() && footerText.isNullOrBlank() && preferentialLabel.isNullOrBlank()

    companion object {
        val NONE = QueueBranding()

        fun fromJson(obj: JSONObject?): QueueBranding {
            if (obj == null) return NONE
            return QueueBranding(
                primaryColor = obj.color("primary_color"),
                accentColor = obj.color("accent_color"),
                backgroundColor = obj.color("background_color"),
                headerBgColor = obj.color("header_bg_color"),
                headerTextColor = obj.color("header_text_color"),
                logoUrl = obj.text("header_logo_url"),
                footerText = obj.text("footer_text"),
                preferentialLabel = obj.text("preferential_label"),
            )
        }

        /**
         * Converte `#RRGGBB` em cor.
         *
         * Devolve `null` — e não uma cor de erro — quando o valor não presta. A constraint
         * `queue_branding_colors` já barra formato inválido no banco, mas o app não pode depender
         * disso: uma cor ilegível aqui viraria tela preta na loja, que é pior do que ignorar o
         * campo e manter o tema padrão.
         */
        private fun JSONObject.color(key: String): Int? {
            val raw = text(key) ?: return null
            return runCatching { Color.parseColor(raw) }.getOrNull()
        }

        private fun JSONObject.text(key: String): String? =
            optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
    }
}
