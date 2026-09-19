package com.werebug.androidnetcat

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.werebug.androidnetcat.databinding.ActivityNetcatHomeBinding

class AndroidNetcatHome : AppCompatActivity(), View.OnClickListener {

    private lateinit var binding: ActivityNetcatHomeBinding

    // 持有 NetcatSession 强引用，防止 worker 的 WeakReference 导致其被回收
    private var netcatSession: NetcatSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetcatHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartNetcat.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_start_netcat -> {
                startNetcatSessionActivity(binding.etNcCommandLine.text.toString())
            }
        }
    }

    private fun startNetcatSessionActivity(cmd: String) {
        val session = NetcatSession(this)
        netcatSession = session
        session.show(cmd)
    }
}