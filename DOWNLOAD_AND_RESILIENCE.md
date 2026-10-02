# Download real de conteúdo + alta disponibilidade (X96)

Este documento registra o que foi implementado e, principalmente, **os bugs reais
encontrados e corrigidos** testando em hardware de produção (TV box X96 real) — para
que a próxima pessoa a investigar um sintoma parecido não precise redescobrir a causa
do zero.

## 1. Download real (não é cache)

O MPlayer nunca usou cache de streaming — ele sempre baixou o arquivo inteiro antes de
tocar (`ManifestManager.downloadToFile()`, escrita atômica via `.tmp` + rename). A única
mudança foi **onde** esse arquivo fica:

- Antes: `context.getExternalFilesDir(null)/media/` — pasta privada do app, não navegável
  por um gerenciador de arquivos comum.
- Agora: `/storage/emulated/0/mplayer_downloads/` — pasta pública, visível em qualquer
  gerenciador de arquivos (`ManifestManager.getMediaDir()`, único ponto de verdade,
  reaproveitado por `PlayerActivity.buildLocalPlaylist()`).

### Permissões necessárias

Gravar em pasta pública exige mais que gravar na pasta privada do app:

| Versão Android | Mecanismo |
|---|---|
| ≤ 9 (API 28) | `WRITE_EXTERNAL_STORAGE` pedido em runtime |
| 10 (API 29) | `WRITE_EXTERNAL_STORAGE` + `android:requestLegacyExternalStorage="true"` no manifesto |
| 11+ (API 30+) | `MANAGE_EXTERNAL_STORAGE` ("Acesso a todos os arquivos"), concedido via tela de Configurações (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`), não é um dialog comum |

Ver `PlayerActivity.hasStoragePermission()` / `ensureStoragePermissionIfNeeded()` /
`requestAllFilesAccess()`.

## 2. Resiliência ("nunca fica sem rodar")

Painel de rua fica dias sem ninguém no local — o app precisa se recuperar sozinho de
qualquer coisa.

- **`CrashRecoveryManager`**: handler global (`Thread.setDefaultUncaughtExceptionHandler`)
  instalado em `MupaApplication.onCreate()`. Loga o crash (`SharedPreferences`,
  consumível uma vez via `consumeLastCrash()` pro heartbeat) e agenda a reabertura do
  app via `AlarmManager` (2s) antes de matar o processo corrompido.
  **Testado ao vivo**: `adb shell am crash <pkg>` → processo morre → reabre sozinho em
  ~5s, volta a tocar.
- **`BootReceiver`**: `BOOT_COMPLETED`/`QUICKBOOT_POWERON` → reabre o app sozinho após
  queda de energia ou reboot do sistema.
- **`HeartbeatService`**: a cada 2 minutos, upsert em `public.device_heartbeat`
  (projeto Supabase do MPlayer, `iurqddkuihjsmxubibao`) com: item tocando, erro de
  playback, último crash, status/horário do último sync, espaço livre em disco,
  CPU/RAM/temperatura. É o que permite um painel externo (ver `argus-device-hub`)
  alertar quando o device para de rodar conteúdo.

  DDL da tabela (rodar no projeto Supabase do MPlayer, não no do Argos — são projetos
  diferentes):
  ```sql
  create table if not exists public.device_heartbeat (
    device_id text primary key,
    app_version text,
    status text,
    current_item_id text,
    current_item_type text,
    is_playing boolean,
    last_playback_error text,
    last_crash_at timestamptz,
    last_crash_message text,
    last_sync_ok boolean,
    last_sync_at timestamptz,
    storage_free_mb bigint,
    cpu_percent real,
    used_ram_mb integer,
    total_ram_mb integer,
    temperature_c real,
    last_heartbeat_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
  );
  alter table public.device_heartbeat enable row level security;
  create policy "anon can upsert device heartbeat"
    on public.device_heartbeat for all to anon using (true) with check (true);
  ```

## 3. Bugs reais encontrados (testando em hardware de produção)

### 3.1 Corrida em `tryStartOfflinePlayback()` — boot sem internet ficava pedindo conexão pra sempre

**Sintoma**: com conteúdo já baixado e sem internet, o app mostrava "Sem internet.
Aguardando conexão para sincronizar..." indefinidamente, mesmo com tudo certo em disco.

**Causa**: `PlayerEngine.start()` só **lança** a coroutine de playback
(`scope.launch { ... }`) — `prepareInto()` é `suspend` (ExoPlayer/Coil levam tempo real
pra preparar o 1º frame), e só **depois** disso `startPrepared()` seta
`currentItemIdRef`. O código verificava `playerEngine.getCurrentItemId() != null`
**imediatamente** após `tryStartOfflinePlayback()` retornar — quase sempre pegando
`null` mesmo com o playback offline já de fato disparado.

**Fix**: usar o valor de retorno real de `tryStartOfflinePlayback()` (`Boolean`), não
uma checagem de estado com corrida. Ver `PlayerActivity.startLoop()` /
`initialSyncAndPlayback()`.

### 3.2 `DeviceIdentityManager` sobrescrevia a identidade persistida com WiFi desligado

**Sintoma**: depois de desligar o WiFi, o app "esquecia" o cadastro e o conteúdo já
baixado — tentava sincronizar com um `deviceId` desconhecido pelo backend (404).

**Causa raiz**: `resolveStableHardwareId()` tenta, em ordem: (1) MAC da `wlan0` via
`NetworkInterface`, (2) propriedades genéricas de sistema (`ro.serialno`,
`ro.boot.serialno` — a função se chama `resolveZebraStableId()` mas lê propriedades
genéricas, não exclusivas de hardware Zebra). Com o **WiFi desligado**, a interface
`wlan0` some da lista de interfaces de rede e a leitura do MAC falha — caindo no
fallback de `ro.serialno`/`ro.boot.serialno`. **Vários X96 clones baratos vêm de
fábrica com o mesmo placeholder genérico `"1234567890"` nessas propriedades** (não é
único por aparelho). Como esse valor "diferente mas válido" passava em `validate()`
(≥ 8 caracteres), `generateIfMissing()` **sobrescrevia silenciosamente** a identidade
real (derivada do MAC, única) por esse placeholder a cada boot sem WiFi.

**Fix**: uma vez salva e validada, a identidade nunca é trocada implicitamente — só é
gerada do zero se realmente não houver nenhuma salva ainda. Uma troca de identidade
legítima (ex.: device reaproveitado em outro hardware) passa pelo wipe completo
("Apagar dados", no menu admin), não por uma re-resolução automática em background.

> Se outro device apresentar sintoma parecido, confirme primeiro:
> `adb shell getprop ro.serialno` e `ro.boot.serialno` — se vier um valor genérico
> tipo `1234567890`, é esse hardware.

### 3.3 Tela de "Sincronizando..." aparecia mesmo com conteúdo local pronto

**Sintoma**: mesmo quando havia conteúdo já baixado (e às vezes até com internet
disponível), o app mostrava um flash da tela "Sincronizando conteúdos..." por cima do
conteúdo antes de tocar.

**Causa**: `initialSyncAndPlayback()` sempre mostrava o overlay de sincronização antes
de checar qualquer coisa, mesmo quando havia conteúdo local pra tocar imediatamente.

**Fix — regra nova de boot**: havendo conteúdo local, o app toca **direto**, sem
nenhuma tela de sincronização, online ou offline. A checagem de programação nova passa
a rodar **só em segundo plano**, via `refreshInBackground()` (o mesmo caminho silencioso
já usado pelo ciclo periódico) — que só mostra algo na tela quando há uma troca de
playlist de verdade (`STATUS_NEW_PLAYLIST_FOUND`), nunca por uma simples comparação de
manifesto sem mudança.

```kotlin
// PlayerActivity.startLoop()
if (tryStartOfflinePlayback()) {
    scope.launch { runCatching { refreshInBackground() } }
} else {
    initialSyncAndPlayback() // overlay bloqueante só quando NÃO há nada local ainda
}
```

## 4. Como validar isso sem precisar desligar a internet de verdade

A decisão de pular o overlay de sincronização **não depende** de estar online ou
offline — só de `tryStartOfflinePlayback()` ter tido sucesso. Então, com internet
ligada:

1. `adb shell am force-stop com.mupa.player.x96 && adb shell am start -n
   com.mupa.player.x96/com.mupa.player.enterprise.ui.SplashActivity`
2. Checar o log: deve aparecer `manifest_unchanged deviceId=...` (caminho silencioso),
   nunca o status "Sincronizando conteúdos...".
3. Se aparecer isso, o mesmo código roda sem rede — não precisa reproduzir o cenário
   físico pra validar a lógica.

## 5. Changelog de versionCode nesta sessão

| versionCode | Mudança |
|---|---|
| 5 | Download pra pasta pública + permissões por versão de Android |
| 6–7 | Correção da corrida em `tryStartOfflinePlayback()` |
| 8 | Correção do `DeviceIdentityManager` (identidade não é mais sobrescrita) |
| 9 | Regra de boot: nunca mostra overlay de sync com conteúdo local pronto |
