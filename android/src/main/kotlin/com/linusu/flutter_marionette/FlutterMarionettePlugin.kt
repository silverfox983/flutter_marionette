package com.linusu.flutter_marionette

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.longToast
import org.jetbrains.anko.uiThread
import org.json.JSONObject
import java.io.StringReader
import java.util.*
import kotlin.collections.HashMap

class FlutterMarionettePlugin : MethodCallHandler, FlutterPlugin {
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val channel = MethodChannel(binding.getFlutterEngine().dartExecutor, "flutter_marionette")
        channel.setMethodCallHandler(FlutterMarionettePlugin(channel))
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    var methodChannel: MethodChannel
    var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
//    var webView: WebView? = null

    var selector = ""
    var HELPER_CODE = """<!DOCTYPE html> "
        "<html>"
        "<head>"
        "</head>"
        "<body>"
        "<script type = "text/javascript">
        const nativeInputValueGetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').get
const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
const nativeTextAreaValueGetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').get
const nativeTextAreaValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
/* Native requestAnimationFrame doesn't work headless */
window['requestAnimationFrame'] = (function () {
  let last = 0
  let queue = []
  const frameDuration = 1000 / 60
  function rethrow (err) {
      throw err
  }
  function processQueue () {
      const batch = queue
      queue = []
      for (const fn of batch) {
          try {
              fn()
          } catch (err) {
              setTimeout(rethrow, 0, err)
          }
      }
  }
  return function requestAnimationFrame (fn) {
      if (queue.length === 0) {
          const now = performance.now()
          const next = Math.max(0, frameDuration - (now - last))
          last = (next + now)
          setTimeout(processQueue, Math.round(next))
      }
      queue.push(fn)
  }
}())
class TimeoutError extends Error {
  constructor (message) {
      super(message)
      this.name = 'TimeoutError'
  }
}
function sleep (ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
function idle (min, max) {
  return sleep(Math.floor(min + (Math.random() * (max - min))))
}
window['SwiftMarionetteReload'] = function () {
  window.location.reload()
}
window['SwiftMarionetteSetContent'] = function (html) {
  document.open()
  document.write(html)
  document.close()
}
window['SwiftMarionetteSimulateClick'] = async function (selector) {
  const target = document.querySelector(selector)
  target.click()
}
window['SwiftMarionetteSimulateType'] = async function (selector, text) {
  const target = document.querySelector(selector)
  const getter = (target.tagName === 'TEXTAREA') ? nativeTextAreaValueGetter : nativeInputValueGetter
  const setter = (target.tagName === 'TEXTAREA') ? nativeTextAreaValueSetter : nativeInputValueSetter
  target.focus()
  await idle(50, 90)
  let currentValue = getter.call(target)
  for (const char of text) {
      const down = new KeyboardEvent('keydown', { key: char, charCode: char.charCodeAt(0), keyCode: char.charCodeAt(0), which: char.charCodeAt(0) })
      target.dispatchEvent(down)
      const press = new KeyboardEvent('keypress', { key: char, charCode: char.charCodeAt(0), keyCode: char.charCodeAt(0), which: char.charCodeAt(0) })
      target.dispatchEvent(press)
      const ev = new InputEvent('input', { data: char, inputType: 'insertText', composed: true, bubbles: true })
      currentValue += char
      setter.call(target, currentValue)
      target.dispatchEvent(ev)
      await idle(20, 110)
      const up = new KeyboardEvent('keyup', { key: char, charCode: char.charCodeAt(0), keyCode: char.charCodeAt(0), which: char.charCodeAt(0) })
      target.dispatchEvent(up)
      await idle(15, 120)
  }
  const ev = new Event('change', { bubbles: true })
  target.dispatchEvent(ev)
  target.blur()
}
window['SwiftMarionetteWaitForFunction'] = function (fn) {
  return new Promise((resolve, reject) => {
      let timedOut = false
      function onRaf () {
          if (timedOut) return
          if (fn()) return resolve()
          requestAnimationFrame(onRaf)
      }
      setTimeout(() => {
          timedOut = true
          reject(new TimeoutError(`Timeout reached waiting for function to return truthy`))
      }, 30000)
      onRaf()
  })
}
window['SwiftMarionetteWaitForSelector'] = function (selector) {
  if (document.querySelector(selector)) return Promise.resolve()
  return new Promise((resolve, reject) => {
      const observer = new MutationObserver((mutations) => {
          if (document.querySelector(selector)) {
              observer.disconnect()
              resolve()
          }
      })
      setTimeout(() => {
          observer.disconnect()
          reject(new TimeoutError(`Timeout reached waiting for "${selector}" to appear`))
      }, 30000)
      observer.observe(document, {
          childList: true,
          subtree: true,
          attributes: true
      })
  })
}
        </script>"
        "</body>"
        "</html>
        """
    val TEST_HELPER2 = """<!DOCTYPE html> "
        "<html>"
        "<head>"
        "</head>"
        "<body>"
        "<script type = "text/javascript">function showAlert(string) {Android.doAndroidCall(string);}</script>"
        "</body>"
        "</html>
        """


    val TEST_HELPER1 = """<!DOCTYPE html> "
        "<html>"
        "<head>"
        "</head>"
        "<body>"
        "<script type = "text/javascript">function showAlert(string) {Android.doAndroidCall(string);}</script>"
        "</body>"
        "</html>
        """

    companion object {
        var pages: Map<String, WebView> = HashMap() //Map<String, Marionette>()
        var activity: Activity? = null
        var page: WebView? = null
        fun debugTrace(id: String, info: String) {
            Log.i(id, info)
        }

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            debugTrace("Registering", "Registering")

            val channel = MethodChannel(registrar.messenger(), "flutter_marionette")
            activity = registrar.activity()
            channel.setMethodCallHandler(FlutterMarionettePlugin(channel)) //registrar.activity(),
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when {
            call.method == "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            call.method == "init" -> {

                debugTrace("Starting", "Starting1 init")

                var id = UUID.randomUUID().toString()
                page = WebView(activity)
                page?.settings?.javaScriptEnabled = true
                page?.addJavascriptInterface(WebInterface(result), "Android")
                page?.webChromeClient = WebChromeClient()
                debugTrace("id", id)
                page?.loadUrl("")
                pages.plus(Pair(id, page))

                result.success(id)
            }
            call.method == "dispose" -> {
                var id = (call.arguments as Map<String, Any>)["id"] as String
                pages[id]?.destroy()
                pages?.minus(id)//.remove(id)
                debugTrace("dispose", "dispose")

                result.success(id)
            }
            call.method == "click" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                val selector = (call.arguments as Map<String, Any>)["selector"] as String
                pages[id]?.loadUrl("javascript:window.SwiftMarionetteSimulateClick($selector)")
//                activity?.applicationContext?.longToast("Finished Loading")
                debugTrace("selector", selector)

                result.success(selector)
            }
            call.method == "evaluate" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                var script = (call.arguments as Map<String, Any>)["script"] as String

                debugTrace("script", "F " +script)
                debugTrace("Caller Url", "F " + page?.url)


                page?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            //(function () { return $script })();
                            debugTrace("Received Url", "F " + view?.url)
                            view?.evaluateJavascript("(()=>{return ($script)})();", ValueCallback<String> {
                                val jsonReader = JsonReader(StringReader(it))

                                jsonReader.isLenient = true
                                if (jsonReader.peek() != JsonToken.NULL && jsonReader?.peek() == JsonToken.STRING) {
                                    val message = jsonReader.nextString()
                                    if (message != null) {
                                        result.success("\"$message\"")
                                        debugTrace("JSonReader", message)
                                    }
                                }
                            })
                        }

                    }
                }


            }
            call.method == "goto" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                val url = (call.arguments as Map<String, Any>)["url"] as String
                page?.loadUrl(url)
                debugTrace("url", url)

                result.success(url)
            }
            call.method == "setContent" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                val html = (call.arguments as Map<String, Any>)["html"] as String
                pages[id]?.loadUrl("javascript:window.SwiftMarionetteSetContent($html)")

                result.success(html)
            }
            call.method == "type" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                val selector = (call.arguments as Map<String, Any>)["selector"] as String
                val text = (call.arguments as Map<String, Any>)["text"] as String

                pages[id]?.loadUrl("javascript:window.SwiftMarionetteSimulateType($selector, $text)")

                debugTrace("text", text)
                result.success(text)

            }
            call.method == "reload" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                pages[id]?.loadUrl("javascript:window.SwiftMarionetteReload()")
//                pages[id]?.reload()
                debugTrace("reload", "reload")

                result.success(id)
            }
            call.method == "waitForFunction" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                val fn = (call.arguments as Map<String, Any>)["fn"] as String
                pages[id]?.loadUrl("javascript:window.SwiftMarionetteWaitForFunction($fn)")
                debugTrace("fn", fn)

                result.success(fn)
            }
            call.method == "waitForNavigation" -> {

                val id = (call.arguments as Map<String, Any>)["id"] as String
                val page = pages[id]
                val loaded = page?.progress
                debugTrace("loaded", "loaded")

                result.success(id)

            }
            call.method == "waitForSelector" -> {
                val id = (call.arguments as Map<String, Any>)["id"] as String
                val selector = (call.arguments as Map<String, Any>)["selector"] as String
                this.selector = selector
                pages[id]?.loadUrl("javascript: window.SwiftMarionetteWaitForSelector($selector)")//!.waitForSelector(selector).flutter(result)
                debugTrace("waitForSelector", "waitForSelector $selector")
                result.success(selector)
            }
            else -> result.notImplemented()
        }

    }

    constructor(methodChannel: MethodChannel) {//activity: Activity,
//        this.activity = activity
        this.methodChannel = methodChannel
        this.methodChannel.setMethodCallHandler(this)
        debugTrace("Starting", "Starting")

        activity?.applicationContext?.longToast("Starting")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            this.activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPaused(p0: Activity?) {
                }

                override fun onActivityResumed(p0: Activity?) {

                }

                override fun onActivityStarted(p0: Activity?) {
                    debugTrace("Starting", "onActivityStarted")

                }

                override fun onActivityDestroyed(p0: Activity?) {
                }

                override fun onActivitySaveInstanceState(p0: Activity?, p1: Bundle?) {
                    debugTrace("Starting", "onActivitySaveInstanceState")

                }

                override fun onActivityStopped(p0: Activity?) {
                }

                override fun onActivityCreated(p0: Activity?, p1: Bundle?) {
                    debugTrace("Starting", "onActivityCreated")

                }
            }
        }

    }

    class WebInterface {
        val result: Result

        constructor(result: Result) {
            this.result = result
        }

        @JavascriptInterface
        fun doAndroidCall(message: String) {
            debugTrace("message", message)
//            result.success(message)
        }
    }


}


