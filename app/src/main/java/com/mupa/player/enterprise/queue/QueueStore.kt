package com.mupa.player.enterprise.queue

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mupa.player.enterprise.storage.settingsDataStore
import com.mupa.player.enterprise.utils.CryptoUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Credencial de dispositivo do MUPA Queue (CONTRATO §1). Serial + segredo de 256 bits. */
data class QueueCredentials(val serial: String, val secret: String) {
    /**
     * Não basta estar preenchido: os dois valores viram **header HTTP**, e o OkHttp lança
     * `IllegalArgumentException` diante de qualquer caractere de controle.
     *
     * Isso derrubou o player em campo em 2026-08-20. O operador colou no campo do segredo o bloco
     * de duas linhas inteiro que o SQL devolve (a linha do serial, quebra, a linha do segredo). O
     * CR ficou no meio do valor e a exceção subia de dentro da coroutine até a main — crash em
     * laço, um por relançamento. `trim()` não pegava, porque o CR não estava nas pontas.
     */
    val isValid: Boolean
        get() = serial.isNotBlank() && secret.isNotBlank() &&
            isHeaderSafe(serial) && isHeaderSafe(secret)
}

/** ASCII imprimível: o intervalo que um valor de header HTTP aceita sem escape. */
private fun isHeaderSafe(value: String): Boolean = value.all { it.code in 0x20..0x7E }

/**
 * Persistência local do módulo de fila, sobre o `settingsDataStore` já existente.
 *
 * O segredo é gravado passando por [CryptoUtils], que usa chave derivada de semente fixa no APK —
 * **isso é ofuscação, não proteção**: quem tiver acesso root ao aparelho e ao APK recupera o valor.
 * O que de fato limita o estrago é o escopo da credencial (um setor, três ações) e a trilha em
 * `queue_ticket_events`. Guardar em Keystore exigiria API 23+ e o flavor `legacy` é 21.
 */
class QueueStore(private val context: Context) {

    private object Keys {
        val secret = stringPreferencesKey("queue_device_secret")
        val serialOverride = stringPreferencesKey("queue_device_serial")
        val pin = stringPreferencesKey("queue_setup_pin")
        val enabled = booleanPreferencesKey("queue_enabled")
        val configCache = stringPreferencesKey("queue_config_cache")
        val keyPlay = intPreferencesKey("queue_key_play")
        val keyNext = intPreferencesKey("queue_key_next")
        val keyBack = intPreferencesKey("queue_key_back")
        val revision = intPreferencesKey("queue_revision")
        val moduleEnabled = booleanPreferencesKey("queue_module_enabled")
    }

    /**
     * Contador incrementado a cada mudança de provisionamento (credencial ou teclas).
     *
     * Serve para o player saber se precisa reconstruir o módulo. Sem ele, a alternativa seria
     * reiniciar a cada ciclo STARTED da Activity — e em campo isso é caro: no X96 de bancada, o
     * ARGOS Agent relança o player a cada ~63 s pelo autostart, o que derrubaria e recriaria o
     * socket do Realtime uma vez por minuto. É a mesma armadilha que o `RealtimeCommandChannel` já
     * documenta para `device_commands`.
     */
    suspend fun revision(): Int = context.settingsDataStore.data.first()[Keys.revision] ?: 0

    private suspend fun bumpRevision() {
        context.settingsDataStore.edit { it[Keys.revision] = (it[Keys.revision] ?: 0) + 1 }
    }

    /**
     * Chave mestra do módulo de fila, ligada pelo operador nas Configurações.
     *
     * **Default `true`**, e isso é deliberado: parear o aparelho já é o ato explícito de dizer que
     * ele participa da fila. Se o default fosse `false`, todo X96 já pareado pararia de chamar
     * senha na atualização, e o sintoma — controle mudo — é justamente o mais difícil de
     * diagnosticar em loja.
     *
     * O interruptor existe para o caso oposto: desligar a fila num aparelho pareado sem perder o
     * pareamento nem as teclas capturadas. Ex.: TV que passa a rodar só campanha numa data
     * promocional e volta a chamar senha depois.
     */
    suspend fun isModuleEnabled(): Boolean =
        context.settingsDataStore.data.first()[Keys.moduleEnabled] ?: true

    suspend fun setModuleEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.moduleEnabled] = enabled }
        bumpRevision()
    }

    /** `true` quando o aparelho tem credencial gravada — o módulo inteiro fica inerte sem ela. */
    val enabledFlow: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[Keys.enabled] ?: false }
            .distinctUntilChanged()

    suspend fun credentials(): QueueCredentials? {
        val prefs = context.settingsDataStore.data.first()
        val serial = prefs[Keys.serialOverride]?.trim().orEmpty()
        val secret = prefs[Keys.secret]?.let { CryptoUtils.decrypt(it) }?.trim().orEmpty()
        val creds = QueueCredentials(serial, secret)
        return creds.takeIf { it.isValid }
    }

    /**
     * Grava serial e segredo, removendo **todo** espaço em branco — não só das pontas.
     *
     * Colar de um resultado de SQL traz quebra de linha e espaço no meio do valor com facilidade, e
     * um único CR ali dentro tornava a credencial impossível de usar como header. Sanear na
     * gravação impede que o valor sujo chegue a ser persistido.
     */
    suspend fun saveCredentials(serial: String, secret: String) {
        context.settingsDataStore.edit {
            it[Keys.serialOverride] = serial.filterNot { c -> c.isWhitespace() }
            it[Keys.secret] = CryptoUtils.encrypt(secret.filterNot { c -> c.isWhitespace() })
            it[Keys.enabled] = true
        }
        bumpRevision()
    }

    /**
     * Remove a credencial e o cache de configuração.
     *
     * O mapeamento de teclas **fica**: ele é propriedade do controle físico da loja, não do
     * pareamento. Reparear o mesmo X96 depois de trocar o segredo não deve obrigar o operador a
     * recapturar as três teclas.
     */
    suspend fun clearCredentials() {
        context.settingsDataStore.edit {
            it.remove(Keys.secret)
            it.remove(Keys.serialOverride)
            it.remove(Keys.configCache)
            it[Keys.enabled] = false
        }
        bumpRevision()
    }

    /**
     * Configuração da última resposta de `GET /config`.
     *
     * Existe para o player subir com cabeçalho e rodapé corretos depois de um reboot sem internet
     * — cenário comum em loja, onde o X96 volta antes do link. Nunca substitui a busca online: é
     * ponto de partida, sobrescrito assim que a rede responde.
     */
    suspend fun cachedConfig(): QueueDeviceConfig? {
        val raw = context.settingsDataStore.data.first()[Keys.configCache]?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { QueueDeviceConfig.fromJson(JSONObject(raw)) }.getOrNull()
    }

    suspend fun cacheConfig(config: QueueDeviceConfig) {
        context.settingsDataStore.edit { it[Keys.configCache] = config.toJson() }
    }

    /**
     * Mapeamento capturado no próprio aparelho.
     *
     * Tem precedência sobre o `remote_keymap` do servidor: quem capturou está com o controle na
     * mão, na frente da TV, e é a informação mais recente que existe. O painel continua podendo
     * definir o mapa para provisionar em lote — ele vale enquanto ninguém capturar localmente.
     */
    suspend fun localKeymap(): QueueKeymap {
        val prefs = context.settingsDataStore.data.first()
        return QueueKeymap(
            play = prefs[Keys.keyPlay]?.takeIf { it > 0 },
            next = prefs[Keys.keyNext]?.takeIf { it > 0 },
            back = prefs[Keys.keyBack]?.takeIf { it > 0 },
        )
    }

    suspend fun saveLocalKeymap(keymap: QueueKeymap) {
        context.settingsDataStore.edit { prefs ->
            keymap.play?.let { prefs[Keys.keyPlay] = it } ?: prefs.remove(Keys.keyPlay)
            keymap.next?.let { prefs[Keys.keyNext] = it } ?: prefs.remove(Keys.keyNext)
            keymap.back?.let { prefs[Keys.keyBack] = it } ?: prefs.remove(Keys.keyBack)
        }
        bumpRevision()
    }

    /**
     * PIN de entrada no modo configuração (mitigação 2 de ARQUITETURA §8.1).
     *
     * Sem PIN gravado vale [DEFAULT_PIN] — o aparelho precisa ser configurável na primeira vez, e
     * a tela força a troca antes de sair. Trocar o PIN é parte do provisionamento, não opcional.
     */
    suspend fun pin(): String =
        context.settingsDataStore.data.first()[Keys.pin]?.trim()?.ifBlank { null } ?: DEFAULT_PIN

    suspend fun savePin(pin: String) {
        context.settingsDataStore.edit { it[Keys.pin] = pin.trim() }
    }

    suspend fun isDefaultPin(): Boolean = pin() == DEFAULT_PIN

    companion object {
        const val DEFAULT_PIN = "1234"
        const val PIN_LENGTH = 4
    }
}
