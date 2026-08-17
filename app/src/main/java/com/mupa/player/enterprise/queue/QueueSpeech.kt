package com.mupa.player.enterprise.queue

/**
 * Texto falado da chamada de senha, em SSML.
 *
 * Separado de [QueueAnnouncer] porque é a única parte do áudio que é pura — não depende de rede,
 * de `Context` nem de reprodução — e é a parte que mais vai ser ajustada com o retorno da loja
 * piloto. Ficando aqui, dá para testar na JVM e mudar a redação sem tocar em nada que faz I/O.
 */
object QueueSpeech {

    /**
     * Monta o SSML da chamada.
     *
     * O número é falado **caractere a caractere** ("A, 1, 2, 5" e não "A cento e vinte e cinco").
     * Com ruído de fundo de loja, dígito a dígito é sensivelmente mais compreensível — e é como
     * painéis de banco e de clínica já falam, então o cliente não precisa aprender nada novo.
     *
     * A vírgula não é enfeite: ela vira pausa curta na prosódia do serviço, que é o que separa os
     * dígitos de forma audível.
     */
    fun ssmlFor(number: String, sectorName: String, voice: String = DEFAULT_VOICE): String {
        val spoken = number.trim().uppercase()
            .filter { it.isLetterOrDigit() }
            .map { it.toString() }
            .joinToString(", ")

        val body = buildString {
            append("Senha. ")
            append(escapeXml(spoken))
            append('.')
            sectorName.trim().takeIf { it.isNotBlank() }?.let {
                append(' ')
                append(escapeXml(it))
                append('.')
            }
        }

        return """<speak version="1.0" xml:lang="pt-BR">""" +
            """<voice name="${escapeXml(voice)}">""" +
            """<prosody rate="$RATE">$body</prosody>""" +
            "</voice></speak>"
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Voz do exemplo de referência: feminina, pt-BR, neural. */
    const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"

    /** Um pouco abaixo do natural: a chamada precisa ser entendida de longe, não soar apressada. */
    private const val RATE = "-8%"
}
