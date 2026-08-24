package com.mupa.player.enterprise.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Renderiza o slide dinâmico (`type: "web"`) numa WebView em tela cheia.
 *
 * Implementa o contrato `docs/2026-08-21-contrato-slide-dinamico-x96.md` §4. Cada item vale por
 * `duration_ms` e depois o motor avança, como faz com imagem e vídeo — a página cuida sozinha do
 * giro interno dos conteúdos, da busca de conteúdo novo e do cache offline.
 *
 * **O player não baixa nada para estes itens.** Diferente de imagem e vídeo, não há arquivo: o
 * conteúdo é a página viva. Tentar pré-carregar produziria HTML salvo como mídia — foi exatamente
 * isso que causou 50 segundos de tela preta no aparelho `.180` em 2026-08-21, antes deste suporte
 * existir.
 */
internal class WebSlideEngine {

    /**
     * Configura a WebView uma única vez por instância.
     *
     * `domStorageEnabled` **não é opcional**: é onde a página guarda o último pacote bom para
     * sobreviver à queda de rede (contrato §5). Sem isso a tela fica em espera até a rede voltar —
     * pelo contrato, é o único jeito de o modo offline quebrar.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        if (webView.getTag(TAG_CONFIGURED) == true) return

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Cache de rede no padrão: as imagens das notícias vêm de CDN e se repetem entre voltas.
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }

        // Fundo escuro em vez do branco padrão: sem isto, cada entrada de item pisca uma tela
        // branca inteira numa TV de loja.
        webView.setBackgroundColor(BACKGROUND_COLOR)

        // Tela de exibição, não navegador: nada de rolagem, barra ou toque.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isClickable = false
        webView.isFocusable = false
        webView.isLongClickable = false
        webView.setOnTouchListener { _, _ -> true }

        // A página usa translateZ(0) nas camadas; sem aceleração de hardware o giro engasga.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Diagnóstico: sem isto, uma página que falha em carregar é indistinguível de uma página
        // que carrega e renderiza vazio — os dois aparecem como tela escura na TV.
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "web_slide_pronta url=$url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                Log.w(TAG, "web_slide_erro url=${request?.url} code=${error?.errorCode}")
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                Log.i(TAG, "web_console ${m.messageLevel()} ${m.message()}")
                return true
            }
        }

        webView.setTag(TAG_CONFIGURED, true)
    }

    /**
     * Carrega a página do item.
     *
     * **Nenhum cabeçalho de autenticação é injetado.** A rota é pública e se identifica pelo
     * `serial` na query — mesma convenção da `device-api-v2` (contrato §4).
     */
    fun load(webView: WebView, url: String) {
        configure(webView)
        Log.i(TAG, "web_slide_load url=$url")
        webView.loadUrl(url)
    }

    /**
     * Suspende a página quando a camada sai de cena — **sem descarregá-la**.
     *
     * `onPause()` congela temporizadores e JavaScript, que é o que interessa: a WebView inativa
     * deixa de competir com a decodificação de vídeo da camada visível.
     *
     * Já `loadUrl("about:blank")` seria errado por dois motivos, e a primeira versão fazia isso:
     *
     * 1. O motor limpa camadas em mais de um ponto do ciclo, inclusive logo após preparar. Medido
     *    no aparelho em 2026-08-24, a página era apagada **35 ms depois** de terminar de carregar,
     *    e a TV mostrava o fundo do app no lugar dela.
     * 2. Mesmo quando o momento acertasse, forçaria recarga completa a cada volta da rotação —
     *    caro em X96, e jogaria fora o `localStorage` que é o cache offline da página
     *    (contrato §5).
     */
    fun clear(webView: WebView) {
        runCatching { webView.onPause() }
            .onFailure { Log.w(TAG, "web_slide_pause_failed", it) }
    }

    /** Retoma o giro da página quando a camada volta a ser exibida. */
    fun resume(webView: WebView) {
        runCatching { webView.onResume() }
    }

    companion object {
        private const val TAG = "MPlayerWebSlide"

        /** `#0A1B3D`, definido no contrato §4 para evitar flash branco entre itens. */
        private val BACKGROUND_COLOR = Color.parseColor("#0A1B3D")

        /** Chave de tag para não reconfigurar a mesma WebView a cada item. */
        private val TAG_CONFIGURED = "web_slide_configured".hashCode()
    }
}
