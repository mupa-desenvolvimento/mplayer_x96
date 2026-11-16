# Conteúdo dinâmico (clima, aniversariantes, notícias, L-Bar, tabela de preços)

Implementa o contrato `2026-11-12-contrato-specs-mplayer.md` do Mupa Connect
(commit `d3df479` em `mupa-desenvolvimento/mupa-connect`).

## O que o app faz

| Item do manifesto | Como é exibido | Onde está |
|---|---|---|
| `type: "web"` (Trilha 1) | WebView em tela cheia pela `duration_ms`. **Nunca baixado.** | `PlaylistEngine.prepareWeb`, `DynamicWebSlide` |
| `type: "price_table"` (Trilha 1) | Tela nativa (setor + produtos, preço pt-BR, selo de promoção). Fundo (`background_url`) é baixado como mídia. Muitos produtos viram páginas dentro da `duration_ms`. | `PriceTableView` |
| `overlays[]` (Trilha 2, L-Bar) | WebView transparente por cima; o vídeo encolhe pro `media_viewport` (300 ms) quando a página diz `ready`. | `OverlayController`, `OverlayScheduler` |
| `is_operante: false` | Tela nativa "Dispositivo inoperante" (o `.svg` que o servidor manda não é decodificado), áudio parado, overlay derrubado. `ping` a cada 60 s. | `PlayerActivity.applyOperationalState`, `DevicePingService` |

Parsing do contrato (Kotlin puro, testável): `managers/DynamicManifest.kt`.

### Regras que protegem o painel

- **Nunca aparece tela de erro nem splash quebrado.** Item `web` que falha (rede, HTTP ≥ 400, timeout de 12 s,
  renderer morto, `Uncaught SyntaxError`, página que não monta em 8 s, ponte dizendo `empty`) é **pulado** e entra em
  cooldown (2 min; 3 min para `empty`). Se nada for exibível, aparece a marca e o app tenta de novo a cada 15 s.
- **A página é pré-carregada escondida** na camada inativa durante o item anterior (crossfade sem tela branca) e fica
  congelada (`WebView.onPause`) até entrar no ar. Uma WebView viva por camada, no máximo; destruída ao sair.
- **Offline:** sem rede usa `LOAD_CACHE_ELSE_NETWORK` (cache HTTP + `localStorage` da própria página). URL que **nunca**
  carregou neste aparelho é pulada direto, sem esperar timeout.
- **Trilha 1 nunca espera a Trilha 2.** `OverlayController` só observa (`onCycleStart`, `onItemShown`).
- **Regra defensiva 5.4:** durante item `web`/`price_table` o overlay some e o vídeo volta a 0,0,1,1.
- **Viewport:** a TV roda a 240 dpi, então a página enxergava 1280×720. O app reescreve o meta viewport para
  `width=1920` (1080 em retrato), como o contrato promete. Verificado: a página passa a ver 1920×1080.
- **Ponte `MupaOverlayBridge`** registrada em **todas** as WebViews (contrato v2.1):
  `setOverlayState("ready"|"empty")` e `setPageState("mounted"|"error", detalhe)`. Em slide de tela cheia, `empty` e
  `error` pulam o slide na hora; `mounted` libera o item. O marcador `#mupa-bootstrap` fica de reserva.
- **`media_viewport`** é sempre **porcentagem 0–100** (`x`,`y`,`w`,`h`), com `object_fit` e `border_radius` (px de design de
  1920, escalado pela largura real da tela e aplicado como recorte arredondado do container do vídeo encolhido).

## Contrato

A fonte da verdade é o **contrato v2.1** (`2026-11-12-contrato-specs-mplayer.md`, Mupa Connect). Pontos que o player depende:

- Host oficial `https://midias.mupa.app` (o `app.mupa.app` foi descontinuado). O app usa a URL que vem no manifesto.
- `start_ms` do overlay é contado a partir do início (índice 0) de **cada ciclo** da Trilha 1; sem sobreposição entre overlays.
- Estado inoperante: o servidor manda `device-inoperative.png`; o app desenha a própria tela nativa (funciona offline).
- Consumo só via Edge Functions: a chave `anon` **não** lê mais `playlist_items` (401).
- **v1 × v2:** `device-api` (v1) emite `duration` em segundos e volume 0–100; `device-api-v2` emite `duration_ms` e 0–1.
  O parser de itens aceita os dois. O app consome só o v2 (`ManifestManager.fetchManifest`).
- Um layout do Studio com `widget_playlist` é feito para **overlay**. Vinculado como item `web` de tela cheia, o slot da
  playlist aparece como um retângulo preto (visto na TV de bancada).

## Histórico: bloqueio do WebView 84 (resolvido)

Até 2026-11-12 o SPA da Mupa Connect não executava no WebView 84 das boxes (`Uncaught SyntaxError: Unexpected token '||='`,
bundle sem `build.target`). A equipe publicou `build.target: ["chrome80","safari13"]` + polyfills e o bundle em produção
(`index-By0hJQno.js`) passou a ter 0 ocorrências de `||=`/`&&=`/`??=`. O app continua se protegendo caso volte a acontecer:
erro fatal de script ou página que não monta em 8 s **pula o item** (overlay recarrega com backoff de 30 s até 10 min).
O relatório enviado à equipe está em `CONTRATO_MUPA_CONNECT_x96.md`.

- Playlists anteriores da TV tinham banners `.avif`; o Android 9 não decodifica AVIF. **Não testado** — conferir se aparecem.

## Como testar (build debug)

Para exercitar casos que o manifesto real da TV não tem (ou injetar um item `web`/`price_table`):

1. `./gradlew :app:assembleLegacyDebug` e instalar (`adb install -r`). O Gradle precisa de JDK 17.
2. Conceder armazenamento: `adb shell pm grant com.mupa.player.x96 android.permission.WRITE_EXTERNAL_STORAGE`.
3. Empurrar um manifesto de teste: `adb push manifest.override.json /sdcard/mplayer_downloads/` — só é lido em **build
   debug** (`ManifestManager.fetchManifest`) e substitui o manifesto do servidor. **Apague ao terminar** (`adb shell rm`).
4. Páginas de teste via HTTP local funcionam só no debug (`app/src/debug/res/xml/network_security_config.xml`); o release
   continua bloqueando HTTP em claro.
5. Logs: `adb logcat -s MPlayerWeb MPlayerPlaylist MPlayerOverlay` (`item_shown`, `overlay_show`/`overlay_hide`,
   `web_item_skipped`, `script_fatal`, `console_error`).

Exemplo de `overlays[]`: `{"id":"a","type":"web","url":"https://…/tv/dinamico?...&overlay=1","start_ms":0,"duration_ms":25000,
"media_viewport":{"x":0,"y":0,"w":72,"h":80,"border_radius":14,"object_fit":"contain"}}`.

Transparência: se algum firmware X96 mostrar artefato, trocar a camada do overlay para software gravando
`overlay_software_layer=true` em `SharedPreferences("mupa_web_slides")`.

### Verificado na TV 192.168.6.51 (versionCode 13, WebView 84)

- **Template real da Mupa Connect (L-Bar "Teste Layout")**: mídia encolhida a 61,79%×76,92% com cantos arredondados,
  widget "Dica de Nutrição", rodapé de notícias com QR code — dados reais de `content-device-feed`.
- Regra defensiva: ao entrar um item `web`, o overlay some no mesmo instante (`overlay_hide`) e volta no próximo vídeo.
- Slide `web` em tela cheia, viewport 1920×1080, pré-carga escondida + crossfade.
- Itens pulados sem travar: `empty`, 404, `ERR_CLEARTEXT_NOT_PERMITTED` e a página real quando quebrada (`script_fatal`).
- Tabela de preços: 14 produtos → 2 páginas (1/2, 2/2), linhas de promoção, preço em R$.
- Sem falso "nova programação" (o manifesto é comparado só na subárvore `manifest`).
- Testes unitários: 29 (`DynamicManifestTest`, `OverlaySchedulerTest`, `ManifestParsingTest`).

### Não verificado

- **Offline** com slide `web` (adb é por Wi-Fi; desligar a rede derruba a sessão). Os assets do SPA são `immutable`, então o
  cache HTTP deve servir; falta confirmar na prática.
- Overlay com `object_fit: "cover"`, tela em retrato, TV 4K, e a tabela de preços com imagem de fundo real.
- Slide `web` **em tela cheia** com um módulo legado real (clima, notícias, aniversariantes): ainda não há um na playlist da TV.
- `setPageState` foi implementado conforme o contrato, mas só se observou o caminho de reserva (`#mupa-bootstrap`)
  e o resultado final; não há log do sinal `mounted`/`error` chegando.
