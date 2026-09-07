package com.antidaze.pocketvm.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.antidaze.pocketvm.R
import com.antidaze.pocketvm.data.VmRepository
import com.antidaze.pocketvm.engine.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "New VM" dialog: name, RAM/CPU, and a boot source (import, download, none). */
class CreateVmDialogFragment : DialogFragment() {

    private var pendingUri: Uri? = null
    private var pendingName: String? = null
    private var submitting = false

    private lateinit var repo: VmRepository
    private lateinit var pickedFile: TextView

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingUri = uri
        pendingName = queryDisplayName(uri)
        pickedFile.text = getString(R.string.create_picked, pendingName ?: uri.toString())
        pickedFile.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = VmRepository(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create, null)
        val name = v.findViewById<EditText>(R.id.create_name)
        val ram = v.findViewById<Spinner>(R.id.create_ram)
        val cpu = v.findViewById<Spinner>(R.id.create_cpu)
        val source = v.findViewById<RadioGroup>(R.id.create_source)
        val pick = v.findViewById<Button>(R.id.create_pick)
        pickedFile = v.findViewById(R.id.create_picked)

        source.setOnCheckedChangeListener { _, checked ->
            syncPickVisibility(source, pick)
            // Android 9 guests need at least 2 GB to run system_server.
            if (checked == R.id.src_android) ram.setSelection(2)
        }
        pick.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_title)
            .setView(v)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create_ok, null)
            .create()
            .apply {
                setOnShowListener { dlg ->
                    (dlg as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        onCreateClicked(dlg, name, ram, cpu, source)
                    }
                }
            }
    }

    private fun syncPickVisibility(source: RadioGroup, pick: Button) {
        pick.visibility = if (source.checkedRadioButtonId == R.id.src_import) View.VISIBLE else View.GONE
    }

    /** Hands the download to the foreground service so it survives app switches. */
    private fun enqueueDownload(dlg: AlertDialog, cfg: com.antidaze.pocketvm.data.VmConfig, kind: String, url: String, label: String, ctx: android.content.Context) {
        cfg.preparing = true
        cfg.statusNote = ""
        cfg.pendingKind = kind
        cfg.pendingUrl = url
        repo.saveConfig(cfg)
        DownloadService.start(ctx, cfg.id, kind, url, label)
    }

    private fun onCreateClicked(dlg: AlertDialog, name: EditText, ram: Spinner, cpu: Spinner, source: RadioGroup) {
        val vmName = name.text.toString().trim()
        if (vmName.isEmpty()) {
            name.error = getString(R.string.create_name_required)
            return
        }
        if (submitting) return
        submitting = true
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val ramSel = ram.selectedItem as String
        val ramMb = if (ramSel.endsWith(" GB")) {
            ramSel.removeSuffix(" GB").toInt() * 1024
        } else {
            ramSel.removeSuffix(" MB").toInt()
        }
        val cpus = (cpu.selectedItem as String).toInt()
        val mode = source.checkedRadioButtonId

        val ctx = requireContext()
        val activity = requireActivity() as MainActivity

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cfg = repo.createVm(vmName, ramMb, cpus)
                when (mode) {
                    R.id.src_import -> {
                        val uri = pendingUri ?: throw IllegalStateException(ctx.getString(R.string.create_no_file))
                        val orig = pendingName ?: "disk.img"
                        val destName = when {
                            orig.endsWith(".iso", true) -> "system.iso"
                            orig.endsWith(".qcow2", true) -> "system.qcow2"
                            else -> "system.img"
                        }
                        val dest = repo.importImage(cfg.id, uri, destName)
                        cfg.systemImagePath = dest.absolutePath
                        cfg.systemIsCdrom = destName.endsWith(".iso")
                        repo.saveConfig(cfg)
                    }
                    R.id.src_download -> enqueueDownload(dlg, cfg, DownloadService.KIND_ISO, TEST_ISO_URL, "Alpine Linux ISO", ctx)
                    R.id.src_android -> enqueueDownload(dlg, cfg, DownloadService.KIND_ANDROID, "", "Android 9 image", ctx)
                }
                if (mode != R.id.src_import) {
                    withContext(Dispatchers.Main) {
                        dlg.dismiss()
                        Toast.makeText(
                            ctx, R.string.toast_download_bg, Toast.LENGTH_LONG
                        ).show()
                        activity.refresh()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        dlg.dismiss()
                        activity.refresh()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    submitting = false
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(ctx, e.message ?: "failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TEST_ISO_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-virt-3.20.9-aarch64.iso"
    }
}
