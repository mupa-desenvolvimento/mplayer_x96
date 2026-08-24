package com.mupa.player.enterprise.queue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validação da credencial de dispositivo.
 *
 * Existe por causa de um crash real em campo (2026-08-20): o segredo foi colado com quebra de linha
 * no meio, o OkHttp recusou o header e a exceção subia de dentro da coroutine até a main, derrubando
 * o player em laço — um crash por relançamento do ARGOS.
 */
class QueueCredentialsTest {

    private val serialOk = "409CA776EC6B"
    private val secretOk = "8c0fb2ff374ef691100cb86942747dfa59ca41f3e339df9c002d50ee143e08ac"

    @Test
    fun `credencial limpa e valida`() {
        assertTrue(QueueCredentials(serialOk, secretOk).isValid)
    }

    @Test
    fun `vazio nao e valido`() {
        assertFalse(QueueCredentials("", secretOk).isValid)
        assertFalse(QueueCredentials(serialOk, "   ").isValid)
    }

    @Test
    fun `quebra de linha no meio do segredo e recusada`() {
        // Exatamente o valor que derrubou o player: o bloco de duas linhas do SQL colado inteiro.
        val colado = "serial: $serialOk\r\nsecret: $secretOk"
        assertFalse(QueueCredentials(serialOk, colado).isValid)
    }

    @Test
    fun `caracteres de controle sao recusados isoladamente`() {
        assertFalse(QueueCredentials(serialOk, secretOk + "\n").isValid)
        assertFalse(QueueCredentials(serialOk, secretOk + "\r").isValid)
        assertFalse(QueueCredentials(serialOk, secretOk + "\t").isValid)
        assertFalse(QueueCredentials(serialOk + "\u0000", secretOk).isValid)
    }

    @Test
    fun `acento no valor e recusado`() {
        // Fora do ASCII imprimível: o OkHttp recusaria na montagem do header.
        assertFalse(QueueCredentials("SÉRIE1", secretOk).isValid)
    }
}
