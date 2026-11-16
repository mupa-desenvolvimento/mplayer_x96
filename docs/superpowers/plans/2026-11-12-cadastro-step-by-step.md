# Cadastro do dispositivo em passo a passo — plano de implementação

> **Para quem executar:** use superpowers:executing-plans (execução nativa nesta sessão; o usuário pediu para implementar em seguida).
> Passos com `- [ ]`. **Não há passos de commit:** o usuário decide quando commitar.

**Meta:** trocar o cadastro de tela única por 4 passos (empresa → apelido → filial → tags), no visual do ARGOS Remote, gravando as tags escolhidas em `device_tags`.

**Arquitetura:** regras puras (`RegistrationWizard`, `TagsJson`) testáveis sem Android; `DeviceTagsService` fala com o Supabase; `DeviceRegistrationViewModel` orquestra; `DeviceRegistrationActivity` só renderiza o passo atual.

**Stack:** Kotlin, ViewBinding, Material 1.12 (`ChipGroup`), Retrofit (`SupabaseApi`), JUnit4 + Robolectric já no projeto.

**Spec:** `docs/superpowers/specs/2026-11-12-cadastro-step-by-step-design.md`

## Restrições globais (copiadas da spec)

- Passos: `DEVICE_ID` (só se a identificação automática falhar) → `COMPANY` → `NICKNAME` → `FILIAL` → `TAGS`. Progresso "n de N".
- Perguntas: "Qual é o código da sua empresa?", "Como quer chamar este dispositivo?", "Qual é o número da loja/filial?", "Quais tags este dispositivo deve ter?", "Qual é o ID do dispositivo?".
- Tags: **opcionais**; só `tags.tenant_id = <tenant da empresa>` e `is_active`; o cadastro **nunca** falha por causa delas.
- Cores: fundo `#0B1220 → #0A1628`; roxo `#6C5CE7`; botão gradiente `#6D5DF6 → #4F46E5`; cartão branco, raio 24dp, largura máx. ~520dp.
- Tudo operável só com D-pad/Enter; **Voltar** volta um passo (no primeiro passo, comportamento atual).
- `device_uuid` vem de `device.device_uuid` na resposta de `device-api-v2/manifest`; vínculo em `device_tags` (`device_id = device_uuid`, `tag_id`), upsert com `on_conflict=device_id,tag_id`.
- Não alterar o RPC `create_dispositivo`, o servidor, nem o que ocorre após o cadastro (abre o `PlayerActivity`).

## Foco de revisão (entradas que a spec não cobre e que costumam quebrar)

1. Filial com espaços/acentos/zeros à esquerda (`" 032 "`, `"Centro Norte"`): a pré-seleção da tag de loja deve achar `loja-032` / `loja-centro-norte`, nunca selecionar a tag errada.
2. Lista de tags vazia, resposta inválida ou rede caindo no passo 4: o botão continua habilitado e o cadastro conclui sem tags.
3. Código de empresa inexistente: fica no passo 1 com mensagem, sem avançar nem travar o botão.
4. Duplo clique/Enter repetido em "Continuar": não pode disparar dois cadastros.
5. Voltar no passo 4 e mudar a filial: a seleção de tags já feita pelo usuário não é sobrescrita pela pré-seleção.

## Mapa de arquivos

| Arquivo | Responsabilidade |
|---|---|
| `ui/RegistrationWizard.kt` (novo) | passos, validação, rótulo de progresso, slug/pré-seleção da tag de loja |
| `services/DeviceTagsService.kt` (novo) | `TagsJson.parse`, `listByTenant`, `resolveDeviceUuid`, `link` |
| `ui/DeviceRegistrationViewModel.kt` | estado do wizard + orquestração |
| `ui/DeviceRegistrationActivity.kt` | renderiza o passo (reescrito) |
| `res/layout/activity_device_registration.xml` | novo layout (reescrito) |
| `res/drawable/bg_registration.xml`, `bg_reg_input.xml`, `bg_reg_button.xml`, `bg_reg_chip.xml` (novos) | visual |
| `test/.../RegistrationWizardTest.kt`, `TagsJsonTest.kt` (novos) | testes |

---

### Task 1: `RegistrationWizard` (regras puras)

**Files:** Create `app/src/main/java/com/mupa/player/enterprise/ui/RegistrationWizard.kt`; Test `app/src/test/java/com/mupa/player/enterprise/RegistrationWizardTest.kt`

**Interfaces — Produces:**
```kotlin
enum class RegistrationStep { DEVICE_ID, COMPANY, NICKNAME, FILIAL, TAGS }
data class WizardTag(val id: String, val name: String, val slug: String?, val category: String?, val color: String?)
object RegistrationWizard {
    fun steps(needsDeviceId: Boolean): List<RegistrationStep>
    fun validate(step: RegistrationStep, value: String, companyFound: Boolean): String?   // mensagem de erro ou null
    fun progressLabel(position: Int, total: Int): String                                     // "2 de 4" (position 1-based)
    fun storeSlug(filial: String): String                                                    // espelha o servidor
    fun preselectStoreTagId(tags: List<WizardTag>, filial: String): String?
}
```

- [ ] **Step 1: testes (falhando)** em `RegistrationWizardTest`: `steps(false)` = `[COMPANY,NICKNAME,FILIAL,TAGS]` e `steps(true)` começa em `DEVICE_ID`; `validate(COMPANY,"",false)` = "Informe o código da empresa."; `validate(COMPANY,"101MUP",false)` = "Empresa não encontrada."; `validate(COMPANY,"101MUP",true)` = null; `NICKNAME` vazio = "Informe um nome para o dispositivo."; `FILIAL` vazio = "Informe o número da loja."; `DEVICE_ID` vazio = "Informe o ID do dispositivo."; `TAGS` sempre null; `progressLabel(2,4)` = "2 de 4"; `storeSlug("1")`="loja-1", `storeSlug(" 032 ")`="loja-032", `storeSlug("Centro Norte")`="loja-centro-norte", `storeSlug("São José")`="loja-sao-jose"; `preselectStoreTagId` acha por slug (`"loja-1"` para `"1"`), acha por nome `"Loja 1"` sem slug, **não** casa `"loja-10"` com filial `"1"`, devolve null sem filial/sem tag.
- [ ] **Step 2:** `./gradlew :app:testLegacyDebugUnitTest --tests "*RegistrationWizardTest"` → falha (símbolos inexistentes). (JDK 17: `JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"`.)
- [ ] **Step 3:** implementar em `RegistrationWizard.kt`. `storeSlug` replica o servidor: `"loja-" + trim → NFD sem diacríticos → minúsculas → espaços viram "-" → remove o que não for [\w-] → colapsa "-"`. Nome esperado da tag de loja: `"Loja N"` (ou a própria filial se já começar com "loja ").
- [ ] **Step 4:** rodar os testes → passam.

### Task 2: `DeviceTagsService` + `TagsJson`

**Files:** Create `services/DeviceTagsService.kt`; Test `TagsJsonTest.kt`

**Interfaces — Consumes:** `WizardTag` (Task 1); `SupabaseClient.createApi()` com `getRaw(url)`, `postJson(url, body)`, `upsertJson(url, body)`; `BuildConfig.SUPABASE_COMPANIES_URL` (base do projeto = tudo antes de `/rest/v1`).
**Produces:**
```kotlin
object TagsJson { fun parse(json: String): List<WizardTag> }   // ignora sem id/nome ou is_active=false; ordena por categoria, depois nome (sem diferenciar maiúsculas)
sealed class TagsResult { data class Success(val tags: List<WizardTag>) : TagsResult(); data class Error(val message: String) : TagsResult() }
class DeviceTagsService(context: Context) {
    suspend fun listByTenant(tenantId: String): TagsResult
    suspend fun resolveDeviceUuid(serial: String): String?      // POST device-api-v2/manifest {"serial"} → device.device_uuid; null em qualquer falha
    suspend fun link(deviceUuid: String, tagIds: List<String>): Boolean   // um upsert por tag em device_tags?on_conflict=device_id,tag_id; true só se todos passarem
}
```

- [ ] **Step 1: testes** em `TagsJsonTest`: JSON com 3 tags (uma `is_active:false`, uma sem `name`) devolve 1; ordenação por categoria depois nome; `parse("garbage")` e `parse("{}")` = lista vazia; campos opcionais nulos viram `null`.
- [ ] **Step 2:** rodar → falha. **Step 3:** implementar `TagsJson` e o serviço (URL de tags: `<base>/rest/v1/tags?select=id,name,slug,category,color,is_active&tenant_id=eq.<id>&is_active=eq.true&order=category.asc,name.asc`). **Step 4:** rodar → passa.

### Task 3: `DeviceRegistrationViewModel`

**Files:** Modify `ui/DeviceRegistrationViewModel.kt`

**Interfaces — Consumes:** Tasks 1–2. **Produces** (estado e ações que a Activity usa):
```kotlin
data class DeviceRegistrationUiState(
    /* campos atuais + */ val needsDeviceId: Boolean = false, val stepIndex: Int = 0,
    val stepError: String? = null, val tags: List<WizardTag> = emptyList(), val tagsLoading: Boolean = false,
    val tagsError: String? = null, val selectedTagIds: Set<String> = emptySet(), val tagsWarning: String? = null,
) { val steps: List<RegistrationStep>; val currentStep: RegistrationStep }
fun setNeedsDeviceId(value: Boolean)
fun onContinue()                 // valida o passo; COMPANY faz a busca se preciso; TAGS chama register()
fun onBack(): Boolean            // true se voltou um passo; false se já estava no primeiro
fun toggleTag(id: String)
```

- [ ] **Step 1:** estender o estado (valores padrão preservam quem já constrói). `setCompanyCode/Nickname/Filial/DeviceId` limpam `stepError`.
- [ ] **Step 2:** `onContinue()` ignora chamadas enquanto `isLoading` (foco de revisão 4). Passo `COMPANY`: se `company == null`, `lookupCompany` e só avança se `Found` (erro de rede mostra a mensagem do lookup em `stepError`). Ao entrar em `TAGS`, `loadTags()`: busca por `company.tenantId`; se `selectedTagIds` ainda vazio e o usuário nunca mexeu, aplica `preselectStoreTagId` (foco 5).
- [ ] **Step 3:** `register()` mantém a ordem atual (RPC → validação → cache) e, antes de `setLoadingDone("OK")`, vincula as tags: `resolveDeviceUuid` → `link`. Falha só preenche `tagsWarning`, nunca muda o status (foco 2).
- [ ] **Step 4:** `./gradlew :app:testLegacyDebugUnitTest` → continua verde.

### Task 4: Layout e `DeviceRegistrationActivity`

**Files:** Rewrite `res/layout/activity_device_registration.xml`, `ui/DeviceRegistrationActivity.kt`; Create `res/drawable/bg_registration.xml`, `bg_reg_input.xml`, `bg_reg_button.xml`, `bg_reg_chip.xml`

**Interfaces — Consumes:** estado/ações da Task 3. Mantém ids `offlineOverlay`, `offlineText`, `offlineHint`, `offlineWifiButton`, `deviceIdWatermark` (o overlay "Reconectando..." e o botão de Wi-Fi não mudam).

- [ ] **Step 1:** layout: fundo gradiente, logo `@drawable/ic_mupa_logo`, `MaterialCardView` branco (raio 24dp, largura máx. 520dp), barra de progresso fina, `stepCounter` ("n de N") à direita e `backButton` ("← Voltar") à esquerda, `question`, `answerEdit` (borda roxa), `companyNameText` (verde), `tagsScroll` com `ChipGroup`, `errorText`, `continueButton` ("Continuar →"; no passo `TAGS` "Cadastrar dispositivo"), `loading`, nota de rodapé "Seus dados são usados só pra configurar este painel".
- [ ] **Step 2:** Activity: coleta `uiState`; ao mudar `currentStep` troca pergunta/hint/`inputType`/visibilidades com fade curto e põe o foco (campo de texto; no passo `TAGS`, o primeiro chip ou o botão). Enter/`imeOptions=actionNext` = clicar em "Continuar". `OnBackPressedCallback`: `if (!viewModel.onBack()) finish()`. Chips: `isCheckable`, cor da tag, foco visível, `toggleTag` ao alternar. Aviso `tagsWarning` vira `Toast` antes de abrir o player.
- [ ] **Step 3:** `./gradlew :app:assembleLegacyDebug` → compila.

### Task 5: Verificação na TV de bancada

- [ ] **Step 1:** instalar o debug (`adb install -r`) e abrir a tela de cadastro (limpar o cache de registro do app: `adb shell pm clear com.mupa.player.x96` — **só com OK do usuário**, apaga os dados locais). Sem OK, validar pela tela de cadastro invocada por `adb shell am start` e `screencap`.
- [ ] **Step 2:** percorrer os 4 passos só com `adb shell input keyevent` (DPAD/ENTER/BACK) e `screencap` de cada passo; conferir o erro de empresa inexistente e o Voltar.
- [ ] **Step 3:** atualizar `DYNAMIC_CONTENT.md`? Não — registrar o resultado e a limitação (escrita em `device_tags` não testada) no resumo final.
