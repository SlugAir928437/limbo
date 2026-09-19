package com.werebug.androidnetcat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.werebug.androidnetcat.databinding.ActivityNetcatSessionBinding
import java.lang.ref.WeakReference

/**
 * Netcat 会话控制台，以 MaterialAlertDialog 形式弹出，替代原先独立的 Activity。
 * 由调用方持有强引用（配合 NetcatWorker 内部的 WeakReference）以保持存活。
 */
class NetcatSession(private val context: Context) : View.OnClickListener {

    private lateinit var binding: ActivityNetcatSessionBinding
    private lateinit var worker: NetcatWorker
    private var dialog: AlertDialog? = null

    fun show(ncCmd: String) {
        binding = ActivityNetcatSessionBinding.inflate(LayoutInflater.from(context))

        val ncCmdArgv = ncCmd.split(" ").toMutableList()
        val ncatPath = context.applicationInfo.nativeLibraryDir + "/libncat.so"
        if (ncCmdArgv[0] != "nc" && ncCmdArgv[0] != "ncat") {
            Toast.makeText(context, R.string.error_missing_nc, Toast.LENGTH_SHORT).show()
            return
        }
        ncCmdArgv.removeAt(0)
        ncCmdArgv.add(0, ncatPath)
        val shellWrappedArgv = listOf("/system/bin/sh", "-c", ncCmdArgv.joinToString(" "))

        worker = NetcatWorker(shellWrappedArgv, WeakReference(this))
        dialog = MaterialAlertDialogBuilder(context, R.style.Theme_NetcatSession_Dialog)
            .setTitle(ncCmd)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ -> worker.halt() }
            .setOnDismissListener { worker.halt() }
            .create()

        binding.btnSendText.setOnClickListener(this)
        worker.start()
        dialog?.show()
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_send_text -> {
                val text: String = binding.etNcSendText.text.toString()
                binding.etNcSendText.text.clear()
                worker.addToSendQueue(text)
            }
        }
    }

    fun appendToOutputView(message: String) {
        val newText = "${binding.tvConnection.text}${message}"
        binding.tvConnection.text = newText
        // 有新内容时自动滚动到底部
        binding.scrollConnection.post { binding.scrollConnection.fullScroll(View.FOCUS_DOWN) }
    }

    fun disableMessageViews() {
        binding.etNcSendText.visibility = View.GONE
        binding.btnSendText.visibility = View.GONE
    }
}