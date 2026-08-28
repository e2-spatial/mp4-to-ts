package com.e2spatial.mp4tots

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConverterScreen()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return "video.mp4"
    }

    @Composable
    fun ConverterScreen() {
        var pickedUri by remember { mutableStateOf<Uri?>(null) }
        var pickedName by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf("Pick an MP4 to convert.") }
        var isConverting by remember { mutableStateOf(false) }
        var resultFile by remember { mutableStateOf<File?>(null) }
        val scope = rememberCoroutineScope()

        val pickVideoLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                pickedUri = uri
                pickedName = queryDisplayName(uri)
                resultFile = null
                status = "Selected: $pickedName"
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("MP4 → TS Converter", style = MaterialTheme.typography.headlineSmall)
            Text(status)

            Button(onClick = { pickVideoLauncher.launch(arrayOf("video/mp4")) }) {
                Text("Select MP4")
            }

            Button(
                enabled = pickedUri != null && !isConverting,
                onClick = {
                    val uri = pickedUri ?: return@Button
                    val name = pickedName ?: "video.mp4"
                    isConverting = true
                    status = "Converting..."
                    scope.launch {
                        try {
                            val outDir = File(getExternalFilesDir(null), "converted").apply { mkdirs() }
                            val baseName = name.substringBeforeLast('.', name)
                            val outputFile = File(outDir, "$baseName.ts")

                            val result = withContext(Dispatchers.IO) {
                                val inputFile = Converter.copyToCache(this@MainActivity, uri, name)
                                Converter.convertToTs(inputFile, outputFile) { }
                            }

                            when (result) {
                                is ConversionResult.Success -> {
                                    resultFile = result.outputFile
                                    status = "Done: ${result.outputFile.name}"
                                }
                                is ConversionResult.Failure -> {
                                    status = "Failed: ${result.message}"
                                }
                            }
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        } finally {
                            isConverting = false
                        }
                    }
                }
            ) {
                Text("Convert to .ts")
            }

            if (isConverting) {
                CircularProgressIndicator()
            }

            resultFile?.let { file ->
                Button(onClick = {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "com.e2spatial.mp4tots.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp2t"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Save/share .ts file"))
                }) {
                    Text("Share .ts file")
                }
            }
        }
    }
}
