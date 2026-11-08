package io.github.otto

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A pairing link (otto://pair?…, from `otto pair` on the box): shows the box and addresses it carries, and saves
 * them once the user confirms. The links to the box then reconnect with them.
 */
class PairActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        val uri = intent.data
        val parsed = uri?.let { Pairing.parse(it) } ?: Result.failure(IllegalArgumentException("no link"))
        val p = parsed.getOrElse {
            MaterialAlertDialogBuilder(this)
                .setTitle("Not a pairing link")
                .setMessage(it.message)
                .setPositiveButton("Close") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        val old = Pairing.load(this)
        val message = buildString {
            if (old != null && old.box != p.box) append("This replaces the pairing with ${old.box}.\n\n")
            append("Box: ${p.box}\n")
            append("Browser link: port ${p.browserPort} (pipes ${p.pipePort})\n")
            append("Device link: port ${p.devicePort}\n")
            append("Hub: ${p.hubUrl}\n")
            append("Artifacts: ${p.artifactsUrl}\n")
            if (p.publicArtifactsUrl.isNotEmpty()) append("Public artifacts: ${p.publicArtifactsUrl}\n")
            if (p.panelUrl.isNotEmpty()) append("Panel: ${p.panelUrl}\n")
            append("Tokens: ${mask(p.browserToken)}, ${mask(p.deviceToken)}\n\n")
            append("The box will be able to drive Otto's browser, and to see and control this phone while Otto's handle is on.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Pair with ${p.box}?")
            .setMessage(message)
            .setPositiveButton("Pair") { _, _ ->
                Pairing.save(this, p)
                if (OverlayService.running) OverlayService.send(this, OverlayService.ACTION_RELINK)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun mask(t: String) = if (t.length <= 6) "••••" else t.take(4) + "…" + "(${t.length} characters)"
}
