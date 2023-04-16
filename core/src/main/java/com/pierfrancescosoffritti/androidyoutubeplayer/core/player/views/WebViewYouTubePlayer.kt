package com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting
import com.pierfrancescosoffritti.androidyoutubeplayer.R
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayerBridge
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.toFloat
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*


private class YouTubePlayerImpl(private val webView: WebView) : YouTubePlayer {
  private val mainThread: Handler = Handler(Looper.getMainLooper())
  val listeners = mutableSetOf<YouTubePlayerListener>()

  override fun loadVideo(videoId: String, startSeconds: Float) = webView.invoke("loadVideo", videoId, startSeconds)
  override fun cueVideo(videoId: String, startSeconds: Float) = webView.invoke("cueVideo", videoId, startSeconds)
  override fun play() = webView.invoke("playVideo")
  override fun pause() = webView.invoke("pauseVideo")
  override fun mute() = webView.invoke("mute")
  override fun unMute() = webView.invoke("unMute")
  override fun setVolume(volumePercent: Int) {
    require(volumePercent in 0..100) { "Volume must be between 0 and 100" }
    webView.invoke("setVolume", volumePercent)
  }
  override fun seekTo(time: Float) = webView.invoke("seekTo", time)
  override fun setPlaybackRate(playbackRate: PlayerConstants.PlaybackRate) = webView.invoke("setPlaybackRate", playbackRate.toFloat())
  override fun toggleFullscreen() = webView.invoke("toggleFullscreen")
  override fun addListener(listener: YouTubePlayerListener) = listeners.add(listener)
  override fun removeListener(listener: YouTubePlayerListener) = listeners.remove(listener)

  override fun hideVideoTitle() = webView.invoke("hideVideoTitle")
  override fun disableVideoTitle() = webView.invoke("disableVideoTitle")
  override fun hideTabletPopup() = webView.invoke("hideTabletPopup")
  override fun disableTabletPopup() = webView.invoke("disableTabletPopup")
  override fun hideBranding() = webView.invoke("hideBranding")
  override fun disableBranding() = webView.invoke("disableBranding")
  override fun hideCaption() = webView.invoke("hideCaption")
  override fun hideSettingsMoreOptions() = webView.invoke("hideSettingsMoreOptions")
  override fun disableSettingsMoreOptions() = webView.invoke("disableSettingsMoreOptions")
  override fun hideMoreOptionsPopUp() = webView.invoke("hideMoreOptionsPopUp")

  fun release() {
    listeners.clear()
    mainThread.removeCallbacksAndMessages(null)
  }

  private fun WebView.invoke(function: String, vararg args: Any) {
    val stringArgs = args.map {
      if (it is String) {
        "'$it'"
      }
      else {
        it.toString()
      }
    }
    mainThread.post { loadUrl("javascript:$function(${stringArgs.joinToString(",")})") }
  }
}

internal object FakeWebViewYouTubeListener : FullscreenListener {
  override fun onEnterFullscreen(fullscreenView: View, exitFullscreen: () -> Unit) {}
  override fun onExitFullscreen() {}
}

/**
 * WebView implementation of [YouTubePlayer]. The player runs inside the WebView, using the IFrame Player API.
 */
internal class WebViewYouTubePlayer constructor(
  context: Context,
  private val listener: FullscreenListener,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr), YouTubePlayerBridge.YouTubePlayerBridgeCallbacks {

  /** Constructor used by tools */
  constructor(context: Context) : this(context, FakeWebViewYouTubeListener)

  private val _youTubePlayer = YouTubePlayerImpl(this)
  internal val youtubePlayer: YouTubePlayer get() = _youTubePlayer

  private lateinit var youTubePlayerInitListener: (YouTubePlayer) -> Unit

  internal var isBackgroundPlaybackEnabled = false

  internal fun initialize(initListener: (YouTubePlayer) -> Unit, playerOptions: IFramePlayerOptions?) {
    youTubePlayerInitListener = initListener
    initWebView(playerOptions ?: IFramePlayerOptions.default)
    this.webViewClient = MyWebViewClient()
    this.setOnLongClickListener { true }
//    this.setOnKeyListener(this)
//    this.isClickable = false;
//    this.isLongClickable = false;
//    this.setOnLongClickListener(null)
//    this.setOnClickListener(null)
  }

  // create new set to avoid concurrent modifications
  override val listeners: Collection<YouTubePlayerListener> get() = _youTubePlayer.listeners.toSet()
  override fun getInstance(): YouTubePlayer = _youTubePlayer
  override fun onYouTubeIFrameAPIReady() = youTubePlayerInitListener(_youTubePlayer)
  fun addListener(listener: YouTubePlayerListener) = _youTubePlayer.listeners.add(listener)
  fun removeListener(listener: YouTubePlayerListener) = _youTubePlayer.listeners.remove(listener)

  override fun destroy() {
    _youTubePlayer.release()
    super.destroy()
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun initWebView(playerOptions: IFramePlayerOptions) {
    settings.apply {
      javaScriptEnabled = true
      mediaPlaybackRequiresUserGesture = false
      cacheMode = WebSettings.LOAD_DEFAULT
    }

    addJavascriptInterface(YouTubePlayerBridge(this), "YouTubePlayerBridge")

    val htmlPage = readHTMLFromUTF8File(resources.openRawResource(R.raw.ayp_youtube_player))
      .replace("<<injectedPlayerVars>>", playerOptions.toString())

    loadDataWithBaseURL(playerOptions.getOrigin(), htmlPage, "text/html", "utf-8", null)

    webChromeClient = object : WebChromeClient() {

      override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        super.onShowCustomView(view, callback)
        listener.onEnterFullscreen(view) { callback.onCustomViewHidden() }
      }

      override fun onHideCustomView() {
        super.onHideCustomView()
        listener.onExitFullscreen()
      }

      override fun getDefaultVideoPoster(): Bitmap? {
        val result = super.getDefaultVideoPoster()
        // if the video's thumbnail is not in memory, show a black screen
        return result ?: Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
      }
    }
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    if (isBackgroundPlaybackEnabled && (visibility == View.GONE || visibility == View.INVISIBLE)) {
      return
    }

    super.onWindowVisibilityChanged(visibility)
  }

//  override fun onLongClick(v: View?): Boolean {
////    Log.i("onLongClick", "WebViewYouTubePlayer onLongClick called")
//    return true
//  }

//
//  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
//    Log.i("onKeyDown", event!!.action.toString())
//    return super.onKeyDown(keyCode, event)
//  }
//
//  override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
//    Log.i("onKeyUp", event!!.action.toString())
//    return super.onKeyUp(keyCode, event)
//  }
//
//  override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
//    Log.i("onKeyLongPress", event!!.action.toString())
//    return super.onKeyLongPress(keyCode, event)
//  }
//  override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
//    Log.i("onKey", event!!.action.toString())
//    return event.action == KeyEvent.ACTION_DOWN
//  }
//
}

private class MyWebViewClient : WebViewClient() {

  override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
    return true
  }
}

@VisibleForTesting
internal fun readHTMLFromUTF8File(inputStream: InputStream): String {
  inputStream.use {
    try {
      val bufferedReader = BufferedReader(InputStreamReader(inputStream, "utf-8"))
      return bufferedReader.readLines().joinToString("\n")
    } catch (e: Exception) {
      throw RuntimeException("Can't parse HTML file.")
    }
  }
}
