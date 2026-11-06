package com.github.sky130.suiteki.pro

import android.R.attr.label
import android.R.attr.text
import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.getSystemService
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.handler.CrashHandler


@SuppressLint("StaticFieldLeak")
class MainApplication : Application() {
    companion object {
        private var mContext: Context? = null
        private var mApplication: Application? = null

        val context get() = mContext!!
        val application get() = mApplication!!

        fun openUrl(url: String) {
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun String.copy() {
            val clipboard = context.getSystemService<ClipboardManager>()!!
            val clip = ClipData.newPlainText(this, this)
            clipboard.setPrimaryClip(clip)
        }

        fun String.toast() {
            Toast.makeText(context, this, Toast.LENGTH_SHORT).show()
        }

    }

    override fun onCreate() {
        super.onCreate()
        mContext = this
        mApplication = this
        // release 包里也往 logcat 写 MAC 和收发数据，等于把设备密钥送出去。
        // 项目没开 buildConfig 生成，所以直接用 FLAG_DEBUGGABLE 判断。
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        com.clj.fastble.BleManager.getInstance().enableLog(debuggable).init(this)
        SuitekiManager.init()
        CrashHandler.instance.init(this)
    }
}