package com.sopcam.capture

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 快门反馈。
 *
 * 车间环境下这两样都不能省：有噪音时听不见提示音，戴手套时震动的感知又弱，
 * 所以两个都做、各给一个开关。
 */
class ShutterFeedback(context: Context) {

    private val app = context.applicationContext

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    // MediaActionSound 播的是系统自带的快门音，跟原生相机一致，
    // 而且不受静音键影响的行为也跟系统相机一样，用户不会觉得奇怪
    private val sound: MediaActionSound? = runCatching {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }.getOrNull()

    fun fire(vibrate: Boolean, play: Boolean) {
        if (vibrate) doVibrate()
        if (play) runCatching { sound?.play(MediaActionSound.SHUTTER_CLICK) }
    }

    private fun doVibrate() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            // 一下短促的轻震，不是长震 —— 快门反馈要"脆"，拖泥带水反而像出错
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                @Suppress("DEPRECATION")
                VibrationEffect.createOneShot(28L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        }
    }

    fun release() {
        runCatching { sound?.release() }
    }
}
