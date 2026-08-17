package com.mupa.player.enterprise.queue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do parsing e das regras puras do módulo de fila.
 *
 * Rodam na JVM sem Robolectric: os modelos usam só `org.json`, que já é dependência de teste. O que
 * está coberto aqui é exatamente o que quebra em silêncio na loja — mapeamento de tecla ausente,
 * status terminal tratado como erro, e o `repeated` que o contrato não documenta.
 */
class QueueModelsTest {

    // ---------------------------------------------------------------------------------------
    // Keymap
    // ---------------------------------------------------------------------------------------

    @Test
    fun `keymap vazio nao resolve nenhuma tecla`() {
        val keymap = QueueKeymap.fromJson(JSONObject("{}"))
        assertTrue(keymap.isEmpty)
        assertFalse(keymap.isComplete)
        assertNull(keymap.actionFor(23))
    }

    @Test
    fun `keymap ausente no config nao vira default em codigo`() {
        // Regra do CONTRATO §3: keymap vazio significa "ainda não capturado". Um default embutido
        // faria o X96 chamar senha com a tecla errada, e o erro só apareceria no balcão.
        val config = QueueDeviceConfig.fromJson(
            JSONObject("""{"sector_id":"s1","role":"CALLER","is_active":true}"""),
        )!!
        assertTrue(config.keymap.isEmpty)
    }

    @Test
    fun `keymap resolve acao por keycode`() {
        val keymap = QueueKeymap.fromJson(JSONObject("""{"play":23,"next":22,"back":21}"""))
        assertTrue(keymap.isComplete)
        assertEquals(QueueAction.PLAY, keymap.actionFor(23))
        assertEquals(QueueAction.NEXT, keymap.actionFor(22))
        assertEquals(QueueAction.BACK, keymap.actionFor(21))
        assertNull(keymap.actionFor(19))
    }

    @Test
    fun `keymap detecta tecla ja usada por outra acao`() {
        val keymap = QueueKeymap(play = 23, next = 22)
        assertEquals(QueueAction.NEXT, keymap.conflictFor(22, QueueAction.BACK))
        // Recapturar a mesma tecla para a mesma ação não é conflito.
        assertNull(keymap.conflictFor(23, QueueAction.PLAY))
    }

    @Test
    fun `keycode invalido e ignorado`() {
        val keymap = QueueKeymap.fromJson(JSONObject("""{"play":0,"next":-1}"""))
        assertNull(keymap.play)
        assertNull(keymap.next)
    }

    // ---------------------------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------------------------

    @Test
    fun `config sem sector_id e recusada`() {
        assertNull(QueueDeviceConfig.fromJson(JSONObject("""{"status":"not_found"}""")))
    }

    @Test
    fun `apenas CALLER ativo pode chamar`() {
        fun config(role: String, active: Boolean) = QueueDeviceConfig.fromJson(
            JSONObject("""{"sector_id":"s1","role":"$role","is_active":$active}"""),
        )!!

        assertTrue(config("CALLER", true).canCall)
        assertFalse(config("CALLER", false).canCall)
        assertFalse(config("DISPLAY", true).canCall)
        assertFalse(config("ISSUER", true).canCall)
    }

    @Test
    fun `duracao do overlay e limitada a faixa util`() {
        fun duration(ms: String) = QueueDeviceConfig.fromJson(
            JSONObject("""{"sector_id":"s1","overlay_duration_ms":$ms}"""),
        )!!.overlayDurationMs

        assertEquals(15_000L, duration("15000"))
        // Um zero vindo de configuração errada faria o card sumir antes de ser lido.
        assertEquals(2_000L, duration("0"))
        assertEquals(120_000L, duration("999999999"))
    }

    @Test
    fun `config sobrevive a ida e volta pelo cache`() {
        val original = QueueDeviceConfig.fromJson(
            JSONObject(
                """{"sector_id":"s1","sector_name":"Açougue","role":"CALLER","prefix":"A",
                   "is_active":true,"overlay_duration_ms":9000,"audio_enabled":false,
                   "remote_keymap":{"play":23,"next":22,"back":21},"service_date":"2026-08-17"}""",
            ),
        )!!
        val restored = QueueDeviceConfig.fromJson(JSONObject(original.toJson()))!!
        assertEquals(original, restored)
    }

    // ---------------------------------------------------------------------------------------
    // Estado
    // ---------------------------------------------------------------------------------------

    @Test
    fun `estado sem chamada do dia vem com current nulo`() {
        val state = QueueState.fromJson(
            JSONObject("""{"sector_id":"s1","current":null,"history":[],"waiting_count":3}"""),
        )
        assertNull(state.current)
        assertEquals(3, state.waitingCount)
        assertTrue(state.previousCalls.isEmpty())
    }

    @Test
    fun `ultimas chamadas nao repetem a senha atual`() {
        val state = QueueState.fromJson(
            JSONObject(
                """{"sector_id":"s1",
                   "cursor_position":12,
                   "current":{"ticket_id":"t3","number":"A125","type":"NORMAL","position":12},
                   "history":[
                     {"ticket_id":"t3","number":"A125","type":"NORMAL","position":12},
                     {"ticket_id":"t2","number":"A124","type":"NORMAL","position":11},
                     {"ticket_id":"t1","number":"A123","type":"NORMAL","position":10}],
                   "waiting_count":7}""",
            ),
        )
        assertEquals(listOf("A124", "A123"), state.previousCalls.map { it.number })
    }

    @Test
    fun `senha preferencial e reconhecida como prioritaria`() {
        fun call(type: String) = QueueCall.fromJson(
            JSONObject("""{"ticket_id":"t","number":"P001","type":"$type","position":1}"""),
        )!!

        assertTrue(call("PREFERENCIAL").isPriority)
        assertTrue(call("PRIORIDADE").isPriority)
        assertFalse(call("NORMAL").isPriority)
    }

    // ---------------------------------------------------------------------------------------
    // Resultado da chamada
    // ---------------------------------------------------------------------------------------

    @Test
    fun `next devolve senha chamada`() {
        val outcome = QueueCallOutcome.fromJson(
            JSONObject(
                """{"status":"called","ticket_id":"t9","number":"A126","type":"NORMAL",
                   "position":13,"reopened":false}""",
            ),
        )
        assertTrue(outcome is QueueCallOutcome.Called)
        assertEquals("A126", (outcome as QueueCallOutcome.Called).number)
    }

    @Test
    fun `repeat devolve repeated e nao called`() {
        // Divergência real entre CONTRATO_API §3 e queue_engine.sql:689. Se o cliente só tratasse
        // `called`, toda rechamada cairia em Unknown e a TV não mostraria nada.
        val outcome = QueueCallOutcome.fromJson(
            JSONObject(
                """{"status":"repeated","ticket_id":"t9","number":"A126","type":"NORMAL",
                   "position":13,"repeat_count":2}""",
            ),
        )
        assertTrue(outcome is QueueCallOutcome.Repeated)
        assertEquals(2, (outcome as QueueCallOutcome.Repeated).repeatCount)
    }

    @Test
    fun `estados terminais nao sao erro`() {
        fun outcome(status: String) =
            QueueCallOutcome.fromJson(JSONObject("""{"status":"$status"}"""))

        assertEquals(QueueCallOutcome.Empty, outcome("empty"))
        assertEquals(QueueCallOutcome.AtStart, outcome("at_start"))
        assertEquals(QueueCallOutcome.NoCurrent, outcome("no_current"))
    }

    @Test
    fun `status desconhecido nao explode`() {
        val outcome = QueueCallOutcome.fromJson(JSONObject("""{"status":"futuro"}"""))
        assertEquals(QueueCallOutcome.Unknown("futuro"), outcome)
    }
}
