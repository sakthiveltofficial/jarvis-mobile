package dev.jarvis.bridge.ar

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** JS: requireNativeModule('JarvisAR') for ARCore checks, requireNativeView('JarvisAR') for the AR view. */
class ArModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("JarvisAR")

    /** supported | not_installed | too_old | unsupported | checking | unknown */
    Function("availability") {
      val ctx = appContext.reactContext ?: return@Function "unknown"
      when (ArCoreApk.getInstance().checkAvailability(ctx)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> "supported"
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "not_installed"
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "too_old"
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "unsupported"
        ArCoreApk.Availability.UNKNOWN_CHECKING -> "checking"
        else -> "unknown"
      }
    }

    /** Asks Google Play to install/update Google Play Services for AR. installed | requested | declined | error */
    AsyncFunction("install") { promise: Promise ->
      val act = appContext.currentActivity ?: run { promise.resolve("error"); return@AsyncFunction }
      try {
        when (ArCoreApk.getInstance().requestInstall(act, true)) {
          ArCoreApk.InstallStatus.INSTALLED -> promise.resolve("installed")
          else -> promise.resolve("requested")
        }
      } catch (e: UnavailableUserDeclinedInstallationException) {
        promise.resolve("declined")
      } catch (e: Exception) {
        promise.resolve("error")
      }
    }.runOnQueue(expo.modules.kotlin.functions.Queues.MAIN)

    View(ArView::class) {
      Events("onArStatus", "onFrame", "onTargetFound")

      Prop("orbVisible") { view: ArView, v: Boolean ->
        view.orbVisible = v
        if (!v) view.hideOrb()
      }
      Prop("showPoints") { view: ArView, v: Boolean -> view.showPoints = v }
      Prop("targets") { view: ArView, json: String -> view.setTargets(json) }

      AsyncFunction("capture") { view: ArView, maxSide: Int, promise: Promise -> view.requestCapture(maxSide, promise) }
      AsyncFunction("anchorFromCapture") { view: ArView, captureId: String, u: Double, v: Double, id: String, promise: Promise ->
        view.anchorFromCapture(captureId, u, v, id, promise)
      }
      AsyncFunction("placeOrb") { view: ArView, x: Double, y: Double, promise: Promise -> view.placeOrb(x, y, promise) }
      AsyncFunction("pinAt") { view: ArView, x: Double, y: Double, id: String, remember: Boolean, promise: Promise ->
        view.pinAt(x, y, id, remember, promise)
      }
      AsyncFunction("removeAnchor") { view: ArView, id: String -> view.removeAnchor(id) }
      AsyncFunction("clearAnchors") { view: ArView, prefix: String -> view.clearAnchors(prefix) }
    }
  }
}
