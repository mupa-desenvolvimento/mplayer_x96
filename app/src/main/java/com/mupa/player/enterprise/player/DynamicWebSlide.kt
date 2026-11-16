package com.mupa.player.enterprise.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ponte JS → nativo do contrato v2.1:
 *  - `setOverlayState("ready" | "empty")`: há (ou não) conteúdo para exibir;
 *  - `setPageState("mounted" | "error", detalhe)`: o React montou / falhou no boot.
 */
class MupaOverlayBridge(
    private val onState: (String) -> Unit,
    private val onPageState: (String, String) -> Unit = { _, _ -> },
) {
    @JavascriptInterface
    fun setOverlayState(state: String?) {
        onState(state.orEmpty().trim().lowercase())
    }

    @JavascriptInterface
    fun setPageState(state: String?, detail: String?) {
        onPageState(state.orEmpty().trim().lowercase(), detail.orEmpty())
    }
}

enum class WebLoadResult {
    /** Página carregou (e, se a ponte respondeu, não disse "empty"). */
    OK,

    /** Falhou: erro de rede/HTTP, timeout ou processo do WebView morreu. Nunca exibir. */
    FAILED,

    /** Carregou, mas a página avisou pela ponte que não há conteúdo para este device. */
    EMPTY,
}

/**
 * Uma WebView configurada como pede o contrato do Mupa Connect (Android 9 / X96):
 * JS + DOM storage ligados (o `/tv/dinamico` guarda o último payload em `localStorage` e mostra
 * na hora se a rede cair), sem mixed content, sem rolagem, sem foco.
 *
 * Serve a dois usos: slide em tela cheia na Trilha 1 (fundo opaco) e overlay L-Bar na Trilha 2
 * (fundo transparente). O aviso de falha existe pra que NUNCA apareça a página de erro do
 * Chrome num painel de rua: quem chama pula o item em vez de exibir.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class DynamicWebSlide(
    context: Context,
    private val transparent: Boolean,
    softwareLayer: Boolean = false,
) {
    val webView: WebView = WebView(context)

    /** Largura (px CSS) que a página deve enxergar: 1920 em paisagem, 1080 em retrato (contrato 7.1). */
    private val referenceWidthPx: Int =
        context.resources.displayMetrics.let { if (it.widthPixels >= it.heightPixels) 1920 else 1080 }

    private val stateRef = AtomicReference<String?>(null)
    private val pageResult = AtomicReference<CompletableDeferred<Boolean>?>(null)

    @Volatile
    private var mainFrameFailed = false

    /** A página falhou ao executar o JS (ex.: sintaxe que este WebView não entende) — não vai montar. */
    @Volatile
    private var fatalScriptError = false

    /** A página avisou, pela ponte, que o React montou (`setPageState("mounted")`). */
    @Volatile
    private var pageMounted = false

    @Volatile
    private var destroyed = false

    /** Último estado reportado pela página via [MupaOverlayBridge], ou null se nunca reportou. */
    val bridgeState: String? get() = stateRef.get()

    /** Chamado (em thread de binder do WebView) a cada `setOverlayState`. */
    @Volatile
    var onBridgeState: ((String) -> Unit)? = null

    init {
        webView.apply {
            setBackgroundColor(if (transparent) Color.TRANSPARENT else Color.BLACK)
            if (transparent) {
                setLayerType(if (softwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE, null)
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            isFocusableInTouchMode = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }

            addJavascriptInterface(
                MupaOverlayBridge(
                    onState = { state ->
                        stateRef.set(state)
                        onBridgeState?.invoke(state)
                    },
                    onPageState = { state, detail ->
                        when (state) {
                            "mounted" -> pageMounted = true
                            "error" -> {
                                Log.w(TAG, "page_state_error detail=$detail")
                                fatalScriptError = true
                            }
                        }
                    },
                ),
                "MupaOverlayBridge",
            )

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    val m = message ?: return false
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        val text = m.message().orEmpty()
                        Log.w(TAG, "console_error: $text @ ${m.sourceId()}:${m.lineNumber()}")
                        // `Uncaught SyntaxError` = o bundle nem parseou neste WebView (ex.: `||=` no
                        // Chromium 84). `[MUPA ERROR]` = o guard da própria página desistiu.
                        if (text.startsWith("Uncaught SyntaxError") || text.contains("[MUPA ERROR]")) {
                            fatalScriptError = true
                        }
                    }
                    return false // mantém o log padrão do chromium também
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    mainFrameFailed = false
                    fatalScriptError = false
                    pageMounted = false
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    forceContractViewport(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == "about:blank") return
                    forceContractViewport(view)
                    pageResult.get()?.complete(!mainFrameFailed)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        mainFrameFailed = true
                        Log.w(TAG, "main_frame_error url=${request.url} code=${error?.errorCode} ${error?.description}")
                        // Completa já: onPageFinished ainda vem, mas com a página de erro.
                        pageResult.get()?.complete(false)
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                        mainFrameFailed = true
                        Log.w(TAG, "main_frame_http_error url=${request.url} status=${errorResponse?.statusCode}")
                        pageResult.get()?.complete(false)
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // Sem isso o processo do app morre junto com o renderer do WebView.
                    Log.w(TAG, "render_process_gone crashed=${detail?.didCrash()}")
                    mainFrameFailed = true
                    pageResult.get()?.complete(false)
                    return true
                }
            }
        }
    }

    /**
     * Esta TV box roda a 240 dpi: sem isto a página enxerga 1280x720 (dpr 1,5) em vez dos
     * 1920x1080 que o contrato promete, e qualquer layout do Studio em px sai 1,5x maior e
     * cortado. Reescrever o meta viewport (largura fixa, sem initial-scale) faz o WebView
     * escalar o layout de [referenceWidthPx] px CSS para a tela inteira.
     */
    private fun forceContractViewport(view: WebView?) {
        val js = "(function(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=$referenceWidthPx');})();"
        runCatching { view?.evaluateJavascript(js, null) }
    }

    /**
     * Carrega [url] e espera. Sem rede usa o cache HTTP (a página e o JS já vistos) — o resto
     * do offline é do próprio `/tv/dinamico`, via `localStorage`.
     *
     * [bridgeGraceMs]: depois de carregar, espera esse tempo pela ponte. Se ela disser "empty",
     * devolve [WebLoadResult.EMPTY]; se não responder, segue como OK (página sem ponte).
     *
     * [mountTimeoutMs]: se > 0, só devolve OK se a página realmente montou — o `index.html` do
     * Mupa Connect põe um splash `#mupa-bootstrap` que o React remove ao montar; se o JS quebrar
     * (WebView antigo) o splash "Carregando Mupa…" ficaria no painel. Páginas sem esse marcador
     * contam como montadas.
     */
    suspend fun load(
        url: String,
        timeoutMs: Long,
        offline: Boolean,
        bridgeGraceMs: Long,
        mountTimeoutMs: Long = 0L,
    ): WebLoadResult {
        if (destroyed) return WebLoadResult.FAILED
        stateRef.set(null)
        mainFrameFailed = false
        fatalScriptError = false
        pageMounted = false
        val deferred = CompletableDeferred<Boolean>()
        pageResult.set(deferred)

        webView.settings.cacheMode = if (offline) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        webView.loadUrl(url)

        val ok = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        pageResult.set(null)
        if (!ok) {
            Log.w(TAG, "load_failed url=$url offline=$offline")
            return WebLoadResult.FAILED
        }

        if (bridgeGraceMs > 0L) {
            withTimeoutOrNull(bridgeGraceMs) {
                while (stateRef.get() == null) delay(100L)
            }
        }
        if (fatalScriptError) {
            Log.w(TAG, "script_fatal url=$url")
            return WebLoadResult.FAILED
        }
        if (mountTimeoutMs > 0L && !awaitMounted(mountTimeoutMs)) {
            Log.w(TAG, "not_mounted url=$url fatal=$fatalScriptError")
            return WebLoadResult.FAILED
        }
        return if (stateRef.get() == "empty") WebLoadResult.EMPTY else WebLoadResult.OK
    }

    private suspend fun awaitMounted(timeoutMs: Long): Boolean {
        val mounted = withTimeoutOrNull(timeoutMs) {
            while (!destroyed) {
                if (fatalScriptError) return@withTimeoutOrNull false
                // Sinal explícito do contrato v2.1; o marcador #mupa-bootstrap fica de reserva.
                if (pageMounted || isMounted()) return@withTimeoutOrNull true
                delay(300L)
            }
            false
        }
        return mounted == true
    }

    private suspend fun isMounted(): Boolean = suspendCancellableCoroutine { cont ->
        val started = runCatching {
            webView.evaluateJavascript("(function(){return !document.getElementById('mupa-bootstrap');})()") { r ->
                if (cont.isActive) cont.resume(r == "true")
            }
        }
        if (started.isFailure && cont.isActive) cont.resume(false)
    }

    fun onPause() {
        if (!destroyed) runCatching { webView.onPause() }
    }

    fun onResume() {
        if (!destroyed) runCatching { webView.onResume() }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pageResult.getAndSet(null)?.complete(false)
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface("MupaOverlayBridge")
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
    }

    companion object {
        private const val TAG = "MPlayerWeb"

        /** Mesma noção de "online" do resto do app: há rede ativa com capacidade de internet. */
        fun hasNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
