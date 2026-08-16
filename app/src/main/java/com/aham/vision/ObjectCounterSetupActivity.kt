package com.aham.vision

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.aham.vision.databinding.ActivityObjectCounterSetupBinding

class ObjectCounterSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityObjectCounterSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val labels = assets.open("coco_labels.txt").bufferedReader().readLines()
        val displayLabels = labels.map(::displayName)
        binding.target.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayLabels)
        binding.target.setSelection(labels.indexOf("sports ball").coerceAtLeast(0))
        binding.startCounter.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_COUNT_TARGET, labels[binding.target.selectedItemPosition])
            })
        }
    }

    private fun displayName(label: String): String = when (label) {
        "sports ball" -> "Ping-pong / sports ball"
        "person" -> "Human / person"
        else -> label.replaceFirstChar(Char::uppercase)
    }
}
