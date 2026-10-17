package uk.noammm.aamods.headunit

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import uk.noammm.aamods.PhoneScreen
import uk.noammm.aamods.R
import java.net.ConnectException

// The car screen on the phone's own display: asks Android Auto to start its head unit server, then runs one session
// with it full screen in landscape. Leaving the screen ends the session and stops the server.
class HeadUnitActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var video: SurfaceView
    private var geometry: Geometry? = null
    private var session: Session? = null
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        video = SurfaceView(this).apply { holder.addCallback(this@HeadUnitActivity) }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            setOnTouchListener { view, event ->
                session?.touch(event, view.width, view.height)
                true
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> sizeVideo(right - left, bottom - top) }
        }
        setContentView(root)
        hideSystemBars()
    }

    // Once the window is landscape: the video view gets the size that crops Android Auto's margins off-screen
    private fun sizeVideo(width: Int, height: Int) {
        if (geometry != null || width <= height) return
        val geometry = Geometry(width, height, resources.displayMetrics.densityDpi).also { geometry = it }
        video.holder.setFixedSize(geometry.videoWidth, geometry.videoHeight)
        root.post {
            video.layoutParams = FrameLayout.LayoutParams(geometry.viewWidth, geometry.viewHeight, Gravity.CENTER)
            startSession()
        }
    }

    private fun startSession() {
        val geometry = geometry ?: return
        if (session != null || !surfaceReady) return
        session = Session(applicationContext, geometry, video.holder.surface) { error ->
            runOnUiThread {
                if (error != null && !isFinishing) {
                    val message = if (error is ConnectException) R.string.headunit_unreachable else R.string.headunit_ended
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.also { it.start() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startSession()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    // The decoder draws on this surface, so without it the session can't go on
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (session != null) finish()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        session?.gainVideoFocus()
    }

    override fun onDestroy() {
        session?.stop()
        PhoneScreen.stopServer()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.run {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
