package io.nekohasekai.sfa.ui.profile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.databinding.ActivityQrScanBinding
import io.nekohasekai.sfa.ktx.errorDialogBuilder
import io.nekohasekai.sfa.ui.shared.AbstractActivity
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QRScanActivity : AbstractActivity<ActivityQrScanBinding>() {

    private lateinit var analysisExecutor: ExecutorService
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.profile_add_scan_qr_code)

        analysisExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                setResult(RESULT_CANCELED)
                finish()
        }
    }

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageAnalyzer: ImageAnalysis.Analyzer
    private val onSuccess: (String) -> Unit = { rawValue: String ->
        imageAnalysis.clearAnalyzer()
        if (!onSuccess(rawValue)) {
            imageAnalysis.setAnalyzer(analysisExecutor, imageAnalyzer)
        }
    }
    private val onFailure: (Exception) -> Unit = {
        lifecycleScope.launch {
            errorDialogBuilder(it).show()
        }
    }
    private val vendorAnalyzer = Vendor.createQRCodeAnalyzer(onSuccess, onFailure)

    private fun startCamera() {
        val cameraProviderFuture = try {
            ProcessCameraProvider.getInstance(this)
        } catch (e: Exception) {
            fatalError(e)
            return
        }
        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                fatalError(e)
                return@addListener
            }

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            imageAnalysis = ImageAnalysis.Builder().build()
            imageAnalyzer = vendorAnalyzer ?: ZxingQRCodeAnalyzer(onSuccess, onFailure)
            imageAnalysis.setAnalyzer(analysisExecutor, imageAnalyzer)
            cameraProvider.unbindAll()

            try {
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                fatalError(e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fatalError(e: Exception) {
        lifecycleScope.launch {
            errorDialogBuilder(e).setOnDismissListener {
                setResult(RESULT_CANCELED)
                finish()
            }.show()
        }
    }

    private fun onSuccess(value: String): Boolean {
        try {
            importRemoteProfileFromString(value)
            return true
        } catch (e: Exception) {
            lifecycleScope.launch {
                errorDialogBuilder(e).show()
            }
        }
        return false
    }

    private fun importRemoteProfileFromString(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "sing-box" || uri.host != "import-remote-profile") error("Not a valid sing-box remote profile URI")
        Libbox.parseRemoteProfileImportLink(uri.toString())
        setResult(RESULT_OK, Intent().apply {
            setData(uri)
        })
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (vendorAnalyzer == null) {
            return false
        }
        menuInflater.inflate(R.menu.qr_scan_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_disable_vendor_analyzer -> {
                imageAnalysis.clearAnalyzer()
                imageAnalyzer = ZxingQRCodeAnalyzer(onSuccess, onFailure)
                imageAnalysis.setAnalyzer(analysisExecutor, imageAnalyzer)
                item.isVisible = false
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }


    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    class Contract : ActivityResultContract<Nothing?, Intent?>() {

        override fun createIntent(context: Context, input: Nothing?): Intent =
            Intent(context, QRScanActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
            return when (resultCode) {
                RESULT_OK -> intent
                else -> null
            }
        }
    }

}