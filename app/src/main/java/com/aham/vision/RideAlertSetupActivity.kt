package com.aham.vision

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aham.vision.databinding.ActivityRideAlertSetupBinding

class RideAlertSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityRideAlertSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val choices = mapOf(
            "bird" to binding.bird,
            "deer" to binding.deer,
            "person" to binding.person,
        )
        fun updateStartButton() {
            binding.startTracker.isEnabled = choices.values.any { it.isChecked }
        }
        choices.values.forEach { it.setOnCheckedChangeListener { _, _ -> updateStartButton() } }
        binding.startTracker.setOnClickListener {
            val selected = choices.filterValues { it.isChecked }.keys.toTypedArray()
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ALERT_TARGETS, selected)
            })
        }
        updateStartButton()
    }
}
