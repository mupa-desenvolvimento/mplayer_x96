# mPlayer X96 × Mupa Connect — erros encontrados e como o player deve receber

**Data:** 2026-11-12 · **Origem:** testes do mPlayer Android (X96) numa TV de bancada, contra o servidor de produção
**Para:** equipe Mupa Connect · **Contrato de referência:** `docs/2026-11-12-contrato-specs-mplayer.md` (commit `d3df479`)

Tudo abaixo foi **verificado** (consulta direta aos endpoints, leitura do código em `supabase/functions/*` e logs
da TV). O que é suposição está marcado como tal. Dispositivo de teste: serial `C88AD8E8E4C9`, "Player_Bancada",
playlist `29d75ddf-d744-4dbf-b60f-20319e05c4e8`.

---

## 0. Resumo

| # | Tipo | Assunto | Quem corrige |
|---|---|---|---|
| 1 | 🔴 **Bloqueante** | O SPA `/tv/dinamico` não executa no WebView das boxes (sintaxe JS pós-Chromium 84) | **Mupa Connect** |
| 2 | 🟠 Contrato ≠ servidor | `media_viewport`: servidor manda **%** e `object_fit`; contrato diz 0–1 e `fit` | Mupa Connect (doc ou servidor) |
| 3 | 🟠 Host inexistente | `app.mupa.app` não resolve (NXDOMAIN); o servidor usa `midias.mupa.app` | Mupa Connect |
| 4 | 🟡 Definições pendentes | `start_ms`, ponte em tela cheia, sinal de "montou", `price_table.url` | Mupa Connect (responder) |
| 5 | 🟡 Segurança | Chave `anon` lê `playlist_items` de qualquer playlist | Mupa Connect (revisar RLS) |

O player já trata as falhas sem travar (pula o item). Mas **sem o item 1, nenhum template (clima, notícias,
aniversariantes, frases, L-Bar) aparece** nessas boxes — só imagens e vídeos.

---

## 1. 🔴 Bloqueante — o bundle do SPA não executa no WebView 84

### Evidência
TV X96Max_Plus, Android 9, `com.google.android.webview 84.0.4147.89` (Chromium 84). Ao abrir
`https://midias.mupa.app/tv/dinamico?serial=C88AD8E8E4C9&slide=46dfe075-738b-4781-8ca1-78fa08aef9c7&overlay=1`:

```
[INFO:CONSOLE(2)]  "Uncaught SyntaxError: Unexpected token '||='"
                   source: https://midias.mupa.app/assets/index-BJUAaBgL.js (2)
[INFO:CONSOLE(78)] "[MUPA ERROR] React did not mount within timeout"
```

O mesmo erro ocorreu no bundle anterior (`index-GUg8MtCA.js`) — a republicação **não** resolveu.

### Causa
O chunk de entrada usa sintaxe do **Chromium 85+** (ES2021, *logical assignment*). Contagem no `index-BJUAaBgL.js`
(332 KB): `||=` **3×**, `&&=` **6×**, `??=` **1×**. O primeiro está no próprio polyfill do `index.html`/entrada:

```js
try{let e=globalThis;e.crypto||={},typeof e.crypto.randomUUID!=`function`&&(e.crypto.randomUUID=function(){...
```

Ou seja, o polyfill pensado para WebView antigo é, ele mesmo, sintaxe que o WebView antigo não parseia.
`vite.config.ts` (Vite 8) **não define `build.target`**, então nada é rebaixado.

### Correção exigida
1. `vite.config.ts`:
   ```ts
   build: {
     target: ["chrome80", "safari13"],   // ou menor, se houver boxes com WebView mais antigo
     cssTarget: "chrome80",
     // ...restante igual
   }
   ```
2. **Validar todos os chunks**, não só o de entrada (só o de entrada foi inspecionado aqui; os chunks carregados por
   rota — incluindo `/tv/dinamico` — podem ter mais ocorrências):
   ```bash
   npx es-check es2020 "dist/assets/*.js"      # deve sair sem erros
   grep -lE '\|\|=|&&=|\?\?=' dist/assets/*.js  # deve não listar nada
   ```
3. **APIs** (não são erro de sintaxe, são falta de método no runtime) que também precisam estar ausentes ou
   polifilladas para Chromium 84: `Array.prototype.at` (92), `Object.hasOwn` (93), `structuredClone` (98),
   `Array.prototype.findLast` (97), `String.prototype.replaceAll` (85), `Promise.any` (85). No chunk de entrada
   a busca por esses nomes deu 0; refazer em todo o `dist`.
4. CSS: o contrato já cobre (`backdrop-filter`, `aspect-ratio`, `color-mix`...). Manter e conferir `inset` (87).

### Critério de aceite
- Abrir `/tv/dinamico?serial=<X>&slide=<Y>` e `…&overlay=1` num **WebView 84** (box real ou Chrome 84) e o React
  **montar** (some o `#mupa-bootstrap`), sem `Uncaught SyntaxError` no console.
- Reproduzir sem hardware: Chromium 84 via `npx @puppeteer/browsers install chrome@84.0.4147.89`.

### Observações
- O HTML (`index.html`) é `Cache-Control: no-cache, must-revalidate`; os assets são `public, max-age=31536000,
  immutable`. Isso é **bom** para o modo offline do contrato (o player usa o cache HTTP + `localStorage`).
- O SPA inteiro (332 KB só de entrada) é carregado para exibir um widget. Numa box fraca isso pesa; um entry
  dedicado e enxuto para `/tv/*` é uma melhoria desejável, mas **não** é o bloqueio.

---

## 2. 🟠 `media_viewport`: o servidor não segue o contrato escrito

| Campo | Contrato (`§5.1`) | Servidor real (`extractMediaViewportFromElements`, `_shared/dynamicSlide.ts`) |
|---|---|---|
| `x`,`y`,`w`,`h` | normalizado **0.0–1.0** | **porcentagem 0–100** (ex.: `w: 61.79`, `h: 76.92`) |
| ajuste | `fit: "contain" \| "cover"` | `object_fit: "contain" \| "cover"` |
| cantos | (não existe) | `border_radius` (px, ex.: `14`) |
| padrão ausente | — | `w`/`h` = `100` se o slot não definir |

Payload real recebido (overlay "Teste Layout"):

```json
{"id":"a9132f2e-5193-42a2-b15a-c20f2d2ed36b","slide_id":"46dfe075-738b-4781-8ca1-78fa08aef9c7",
 "layout_id":"ab587bbc-df2a-45da-bf8b-81f4704e20ed","name":"Teste Layout","type":"web",
 "url":"https://midias.mupa.app/tv/dinamico?serial=C88AD8E8E4C9&slide=46dfe075-…&overlay=1",
 "start_ms":0,"duration_ms":28000,
 "media_viewport":{"x":0,"y":0,"w":61.79,"h":76.92,"border_radius":14,"object_fit":"contain"},
 "required_modules":["NEWS","QUOTE","BIRTHDAY"]}
```

**Decisão pedida:** manter o servidor como está e **corrigir o contrato** (recomendado — já está em produção) ou
mudar o servidor para o formato do contrato. O player hoje aceita os dois (qualquer valor > 1 é tratado como %),
mas isso é tolerância, não especificação. Se o servidor passar a emitir `w: 1` querendo dizer 1%, a tolerância
erraria — **definam uma unidade só**.

`border_radius` é enviado mas o player **ainda não o aplica** ao vídeo encolhido (cantos retos).

### Como o player deve receber (formato que implementamos)
- `overlays[]` na raiz **e** em `playlist.overlays` (idênticos). Cada item: `id`, `type:"web"`, `url` (já com
  `&overlay=1`), `start_ms ≥ 0`, `duration_ms ≥ 1000` (o servidor garante ≥ 3000), `media_viewport` ou `null`.
- Ignorados pelo player: `slide_id`, `layout_id`, `name`, `required_modules`.
- `media_viewport = null` → vídeo em tela cheia e o overlay só flutua por cima.

---

## 3. 🟠 Host `app.mupa.app` não existe

- O contrato cita `https://app.mupa.app/tv/...` e `https://app.mupa.app/images/device-inoperative.svg`.
- `nslookup app.mupa.app` → **Non-existent domain** (DNS da LAN `192.168.6.1` e do PC); a TV: `ping: unknown host app.mupa.app`.
- O servidor emite as URLs de slide com `https://midias.mupa.app` (`tvBaseUrl()`: `CONTENT_TV_BASE_URL` →
  `PUBLIC_SITE_URL` → `https://midias.mupa.app`) — **está certo**; o player usa a URL do manifesto, sem host fixo.
- **Mas** o item de dispositivo inoperante (`device-api-v2/index.ts`, linhas ~228/283/339) aponta para
  `https://app.mupa.app/images/device-inoperative.svg`, que **não resolve**. Trocar para o host real **e** servir um
  formato que o Android decodifique sem biblioteca extra (PNG/JPG). O player hoje ignora o SVG e desenha uma tela
  nativa quando `is_operante:false`, mas outros players ficariam sem imagem.
- Corrigir o texto do contrato para `midias.mupa.app` (ou publicar o DNS de `app.mupa.app`).

---

## 4. 🟡 Definições que o contrato não fecha (responder, por favor)

1. **`start_ms` do overlay:** o player assume que conta a partir do **início de cada ciclo da playlist** (instante em
   que o item de índice 0 entra). Confirmar. E: se `start_ms` > duração do ciclo, o overlay nunca aparece — é intencional?
   Há mais de um overlay com janelas sobrepostas? (o player usa o primeiro por `start_ms`).
2. **Ponte em tela cheia:** `window.MupaOverlayBridge.setOverlayState("ready"|"empty")` é chamada também quando a
   página é aberta **sem** `overlay=1` (slide de tela cheia)? O player trata `empty` como "pule este slide"
   (ex.: nenhum aniversariante hoje) e silêncio como "ok" após 2,5 s. Se a ponte só existe em modo overlay, os
   slides vazios aparecerão em branco.
3. **Sinal de "montou":** o player descobre que o React montou pela **ausência** de `#mupa-bootstrap` no DOM. É frágil.
   Propomos um sinal explícito e estável, ex. `window.MupaOverlayBridge.setPageState("mounted" | "error", detalhe)`, ou
   pelo menos declarar `#mupa-bootstrap` como parte do contrato. Também: o guard de 15 s do `index.html` troca o
   conteúdo por uma tela de erro **visível ao público** ("Mupa não conseguiu iniciar neste dispositivo…"). Num painel
   de rua isso não pode aparecer — o player pula o item antes, mas outros clientes não.
4. **`price_table`:** `url` e `background_url` aparecem ambos no exemplo. O player baixa `background_url` como fundo
   e só usa `url` se tiver extensão de imagem. Qual é a semântica de `url`?
5. **`border_radius`:** deve ser aplicado ao vídeo encolhido? (hoje não é).
6. **v1 × v2:** `device-api` (v1) manda `duration` em **segundos** e volume 0–100; `device-api-v2` manda
   `duration_ms` e 0–1. O player consome só o v2 e aceita os dois formatos de item. Confirmar que v1 vai ser descontinuado.

---

## 5. 🟡 Segurança — leitura pública de `playlist_items`

Com a **anon key** (a mesma distribuída nas boxes), `GET /rest/v1/playlist_items?playlist_id=eq.<id>` devolve as
linhas de **qualquer** playlist (inclusive de outros tenants), e `?tipo=eq.dynamic` lista os itens dinâmicos de todos.
`content_slides`, `playlist_overlays`, `playlists` e `dispositivos` voltam vazios para o `anon` (RLS ativo). Se a
leitura aberta de `playlist_items` não é intencional, restringir a política.

---

## 6. Dado de contexto: o manifesto muda a cada consulta (lado do player — já corrigido)

`device.last_player_activity_at` e `device.atualizado` mudam **a cada chamada**, enquanto a subárvore `manifest`
permanece idêntica (verificado: duas chamadas com 20 s de intervalo). O player comparava a resposta inteira e
enxergava "nova programação" a cada ciclo; **já corrigido no player** (compara só `manifest`). Registrado aqui para
que ninguém passe a depender de `device.*` como sinal de mudança de playlist.

---

## 7. Aceite ponta a ponta (quando o item 1 estiver publicado)

Com a TV `C88AD8E8E4C9` e o overlay "Teste Layout" já vinculado à playlist `29d75ddf…`:

1. Abrir a página `…&overlay=1` no WebView 84: React monta, sem `SyntaxError`.
2. A ponte envia `ready` quando há notícias/frases/aniversariantes; o vídeo da Trilha 1 encolhe para o `media_viewport`
   e a barra em L aparece; envia `empty` quando não há conteúdo e o vídeo permanece em tela cheia.
3. Durante um item `web`/`price_table` da Trilha 1 o overlay some e o vídeo volta a tela cheia.
4. Derrubar a rede: a página reabre do cache (HTTP + `localStorage`) sem tela de erro.

### Reproduzir os achados
```bash
# manifesto real (v2) — troque ANON_KEY
curl -s -X POST "https://iurqddkuihjsmxubibao.supabase.co/functions/v1/device-api-v2/manifest" \
  -H "apikey: $ANON_KEY" -H "Authorization: Bearer $ANON_KEY" -H "Content-Type: application/json" \
  --data '{"serial":"C88AD8E8E4C9"}' | python -m json.tool | grep -A12 '"overlays"'

# sintaxe incompatível no bundle em produção
curl -s https://midias.mupa.app/assets/index-BJUAaBgL.js | grep -oE '\|\|=|&&=|\?\?=' | sort | uniq -c

# host do contrato
nslookup app.mupa.app
```
