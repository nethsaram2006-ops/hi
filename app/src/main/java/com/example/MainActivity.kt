package com.example

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorDetailText: TextView
    private lateinit var btnRetryTV: Button
    private lateinit var btnLoadStandard: Button
    private lateinit var btnTranslateSinhala: Button

    // High fidelity SmartTV agent to request Youtube TV's leanback interface without Cobalt restrictions
    private val tvUserAgent = "Mozilla/5.0 (Web0S; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    
    // Fallback standard desktop UA which works universally when TV protocols are rejected
    private val standardDesktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private var currentModeIsTV = true

    // Handler and Runnable for aggressive periodic cookie and storage sync
    // This is vital for Android TV boxes that are hard power-cycled (mains switched off)
    private val cookieSyncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cookieSyncRunnable = object : Runnable {
        override fun run() {
            try {
                CookieManager.getInstance().flush()
                ensureWebViewCacheDirsExist()
            } catch (e: Exception) {
                // Background exception guard
            }
            cookieSyncHandler.postDelayed(this, 10000) // Flush every 10 seconds aggressively
        }
    }

    private fun ensureWebViewCacheDirsExist() {
        try {
            val baseCache = cacheDir
            val codeCacheJs = java.io.File(baseCache, "WebView/Default/HTTP Cache/Code Cache/js")
            if (!codeCacheJs.exists()) {
                codeCacheJs.mkdirs()
            }
            val dummyJs = java.io.File(codeCacheJs, ".keep")
            if (!dummyJs.exists()) {
                dummyJs.createNewFile()
            }

            val codeCacheWasm = java.io.File(baseCache, "WebView/Default/HTTP Cache/Code Cache/wasm")
            if (!codeCacheWasm.exists()) {
                codeCacheWasm.mkdirs()
            }
            val dummyWasm = java.io.File(codeCacheWasm, ".keep")
            if (!dummyWasm.exists()) {
                dummyWasm.createNewFile()
            }
        } catch (e: Exception) {
            // Guard against file system sandboxing constraints
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)

        // Pre-create WebView Code Cache directories to prevent Chromium opendir log warnings
        ensureWebViewCacheDirsExist()

        // Also post-delayed pre-creation to handle any potential early WebView initialization deletion events
        try {
            window.decorView.postDelayed({
                ensureWebViewCacheDirsExist()
            }, 1000)
            window.decorView.postDelayed({
                ensureWebViewCacheDirsExist()
            }, 3000)
        } catch (e: Exception) {
            // Safe fallback
        }

        // Hide standard window decoration and system bars safely once the layout is inflated
        window.decorView.post {
            hideSystemUI()
        }

        // Initialize views
        webView = findViewById(R.id.webView)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        errorView = findViewById(R.id.errorView)
        errorDetailText = findViewById(R.id.errorDetailText)
        btnRetryTV = findViewById(R.id.btnRetryTV)
        btnLoadStandard = findViewById(R.id.btnLoadStandard)
        btnTranslateSinhala = findViewById(R.id.btnTranslateSinhala)

        btnTranslateSinhala.setOnClickListener {
            injectSinhalaTranslationScript()
        }

        // Force hardware acceleration securely for fluent, buttery-smooth video playback on TV processors
        try {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (e: Exception) {
            // Safe fallback
        }

        // Web settings configuration
        val webSettings = webView.settings
        
        // 1. Core Performance & Standards Configuration
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        
        // Disable unnecessary multi-window and prompts on TV devices
        webSettings.setSupportMultipleWindows(false)
        webSettings.mediaPlaybackRequiresUserGesture = false

        // 2. High-Performance Caching
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        // 3. User Agent Configuration
        webSettings.userAgentString = tvUserAgent

        // 4. Session Persistence (Never ask for login again)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }
        cookieManager.flush()

        // 5. TV Remote UX/D-pad Configuration
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.post {
            try {
                webView.requestFocus()
            } catch (e: Exception) {
                // Focus request failure safety
            }
        }

        // 6. Force Pure Dark Theme Aesthetics on WebView
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webSettings.isAlgorithmicDarkeningAllowed = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            webSettings.forceDark = WebSettings.FORCE_DARK_ON
        }

        // WebChromeClient to track load progress
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    loadingProgressBar.visibility = View.VISIBLE
                } else {
                    loadingProgressBar.visibility = View.GONE
                }
            }
        }

        // Prevent launching third-party browsers; let standard navigation load internally inside WebView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                ensureWebViewCacheDirsExist()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Return false to let WebView load the URL internally without recursion
                    return false
                }
                return true // Prevent loading of non-web schemes
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Return false to let WebView load the URL internally without recursion
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                try {
                    // Persist session cookies securely on page milestones
                    CookieManager.getInstance().flush()

                    // Inject custom dark stylesheet logic to guarantee cohesive styling
                    view?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var style = document.createElement('style');
                                style.type = 'text/css';
                                style.innerHTML = 'html, body { background-color: #000000 !important; color: #E0E0E0 !important; color-scheme: dark !important; }';
                                if (document.head) {
                                    document.head.appendChild(style);
                                } else if (document.documentElement) {
                                    document.documentElement.appendChild(style);
                                }
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                    )

                    // Inject skip login / watch as guest bypass logic
                    view?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                function findAndClickSkip() {
                                    var elements = document.querySelectorAll('div, button, a, [role="button"], span');
                                    for (var i = 0; i < elements.length; i++) {
                                        var el = elements[i];
                                        var text = (el.textContent || el.innerText || "").trim().toLowerCase();
                                        if (text === "use youtube signed out" || 
                                            text === "use signed out" || 
                                            text === "watch as guest" || 
                                            text === "skip" || 
                                            text === "guest" || 
                                            text === "use without account" || 
                                            text === "skip sign-in" ||
                                            text === "use without an account") {
                                            
                                            el.focus();
                                            el.click();
                                            
                                            var clickEvt = new MouseEvent('click', { bubbles: true, cancelable: true });
                                            el.dispatchEvent(clickEvt);
                                            return true;
                                        }
                                    }
                                    return false;
                                }
                                var attempts = 0;
                                var interval = setInterval(function() {
                                    attempts++;
                                    var clicked = findAndClickSkip();
                                    if (clicked || attempts > 20) {
                                        clearInterval(interval);
                                    }
                                }, 500);
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                    )
                } catch (e: Exception) {
                    // Guard against potential early detach crashes
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                try {
                    // Prevent total app crash when GPU or content memory limit crashes the underlying Chromium process
                    Toast.makeText(this@MainActivity, "YouTube encountered a glitch. Recovering...", Toast.LENGTH_SHORT).show()
                    val intent = intent
                    finish()
                    startActivity(intent)
                } catch (e: Exception) {
                    // Safe fallback
                }
                return true
            }

            // Error Interception for elegant failover triggers
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val errorCode = error?.errorCode ?: 0
                    val isMainFrame = request?.isForMainFrame ?: false
                    if (isMainFrame) {
                        showErrorScreen("Error: ${error?.description} ($errorCode)")
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                showErrorScreen("Error: $description ($errorCode)")
            }
        }

        // Configure error screen recovery options
        btnRetryTV.setOnClickListener {
            hideErrorScreen()
            currentModeIsTV = true
            webSettings.userAgentString = tvUserAgent
            loadUrlWithWebHeaders("https://www.youtube.com/tv")
        }

        btnLoadStandard.setOnClickListener {
            hideErrorScreen()
            currentModeIsTV = false
            webSettings.userAgentString = standardDesktopUA
            loadUrlWithWebHeaders("https://www.youtube.com")
            Toast.makeText(this, "Loading compatibility layout...", Toast.LENGTH_SHORT).show()
        }

        // Load primary target
        loadInitialPage()

        // Sync cookies periodically starting now
        cookieSyncHandler.post(cookieSyncRunnable)

        // Back button navigation callback to allow moving backwards through web pages
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun loadUrlWithWebHeaders(url: String) {
        val headers = HashMap<String, String>()
        headers["X-Requested-With"] = ""
        webView.loadUrl(url, headers)
    }

    private fun loadInitialPage() {
        hideErrorScreen()
        webView.settings.userAgentString = tvUserAgent
        loadUrlWithWebHeaders("https://www.youtube.com/tv")
    }

    private fun showErrorScreen(details: String) {
        errorDetailText.text = "$details. Choose an option to load."
        errorView.visibility = View.VISIBLE
        loadingProgressBar.visibility = View.GONE
        btnRetryTV.requestFocus() // Give focus to error button for easy remote click
    }

    private fun hideErrorScreen() {
        errorView.visibility = View.GONE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                // Fallback to older flag system if insetsController fails natively on this specific ROM
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        cookieSyncHandler.removeCallbacks(cookieSyncRunnable)
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    private fun injectSinhalaTranslationScript() {
        Toast.makeText(this, "Enabling Sinhala translation...", Toast.LENGTH_SHORT).show()
        val script = """
            (function() {
                // Method 1: Use HTML5 video player option API
                try {
                    var player = document.getElementById("movie_player") || document.querySelector(".html5-video-player");
                    if (player) {
                        player.loadModule("captions");
                        player.setOption("captions", "track", {"languageCode": "si"});
                        player.setOption("captions", "translationLanguage", {"languageCode": "si"});
                        player.setOption("captions", "track", {"languageCode": "si", "is_translation": true});
                    }
                } catch(e) {
                    console.log("Method 1 failed:", e);
                }

                // Method 2: Force Click CC button
                try {
                    var ccBtn = document.querySelector(".ytp-subtitles-button");
                    if (ccBtn && ccBtn.getAttribute("aria-pressed") === "false") {
                        ccBtn.click();
                    }
                } catch (e) {
                    console.log("Method 2 failed:", e);
                }

                // Method 3: Simulate settings menu options for auto translate
                try {
                    var settingsBtn = document.querySelector(".ytp-settings-button");
                    if (settingsBtn) {
                        settingsBtn.click();
                        setTimeout(function() {
                            var menuItems = Array.from(document.querySelectorAll(".ytp-menuitem"));
                            var ccItem = menuItems.find(function(item) {
                                var text = item.textContent || "";
                                return text.includes("Subtitles") || text.includes("CC");
                            });
                            if (ccItem) {
                                ccItem.click();
                                setTimeout(function() {
                                    var subMenuItems = Array.from(document.querySelectorAll(".ytp-menuitem"));
                                    var autoTranslateItem = subMenuItems.find(function(item) {
                                        var text = item.textContent || "";
                                        return text.includes("Auto-translate") || text.includes("translate");
                                    });
                                    if (autoTranslateItem) {
                                        autoTranslateItem.click();
                                        setTimeout(function() {
                                            var langItems = Array.from(document.querySelectorAll(".ytp-menuitem"));
                                            var sinhalaItem = langItems.find(function(item) {
                                                var text = item.textContent || "";
                                                return text.includes("Sinhala") || text.includes("සිංහල");
                                            });
                                            if (sinhalaItem) {
                                                sinhalaItem.click();
                                            }
                                        }, 400);
                                    } else {
                                        var sinhalaItem = subMenuItems.find(function(item) {
                                            var text = item.textContent || "";
                                            return text.includes("Sinhala") || text.includes("සිංහල");
                                        });
                                        if (sinhalaItem) {
                                            sinhalaItem.click();
                                        }
                                    }
                                }, 400);
                            }
                        }, 400);
                    }
                } catch (e) {
                    console.log("Method 3 failed:", e);
                }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }
}
