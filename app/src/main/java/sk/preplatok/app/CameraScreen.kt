package sk.preplatok.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis as CameraAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executors

@Composable
internal fun CameraScreen(invoice: Boolean, onPhoto: (Uri) -> Unit, onGallery: () -> Unit, onError: (String) -> Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted=it }
    val preview=remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FILL_CENTER; implementationMode=PreviewView.ImplementationMode.COMPATIBLE } }
    val capture=remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var warning by remember { mutableStateOf("Držte telefón pevne a klepnutím na displej zaostrite.") }
    var capturing by remember { mutableStateOf(false) }
    val active=remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    LaunchedEffect(Unit) { if(!granted)permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(granted,owner) {
        val executor=Executors.newSingleThreadExecutor()
        var disposed=false
        var provider: ProcessCameraProvider?=null
        if(granted) {
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({
                if(!disposed) runCatching {
                    provider=future.get()
                    val p=Preview.Builder().build().also { it.surfaceProvider=preview.surfaceProvider }
                    var last=0L
                    val analysis=CameraAnalysis.Builder().setBackpressureStrategy(CameraAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if(System.currentTimeMillis()-last>700) {
                                last=System.currentTimeMillis()
                                val plane=image.planes[0];val buffer=plane.buffer;val step=4;val w=image.width/step;val h=image.height/step;val origin=buffer.position()
                                val gray=Gray(w,h,IntArray(w*h) { i -> buffer.get(origin+(i/w*step)*plane.rowStride+(i%w*step)*plane.pixelStride).toInt() and 255 })
                                val q=ImageAnalysis.quality(gray)
                                ContextCompat.getMainExecutor(context).execute { if(!disposed)warning=when { q.blur -> "Možná neostrosť. Klepnite na číslice a držte telefón pevne."; q.glare -> "Silné svetlé miesta. Skontrolujte odlesky a zmeňte uhol."; else -> "Číslice držte v rámčeku. Klepnutím zaostríte." } }
                            }
                        } finally { image.close() }
                    }
                    provider!!.unbindAll()
                    camera=provider!!.bindToLifecycle(owner,androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,p,capture,analysis)
                    preview.setOnTouchListener { _, event -> if(event.action==android.view.MotionEvent.ACTION_UP) { val point=preview.meteringPointFactory.createPoint(event.x,event.y);camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build());preview.performClick() };true }
                }.onFailure { onError("Fotoaparát sa nepodarilo otvoriť. Môžete vybrať fotku z galérie.") }
            },ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed=true;provider?.unbindAll();executor.shutdown() }
    }
    if(!granted) PageColumn {
        Text("Povoľte fotoaparát alebo vyberte fotografiu z galérie.")
        Button(onClick={permission.launch(Manifest.permission.CAMERA)}) { Text("Povoliť fotoaparát") }
        OutlinedButton(onClick=onGallery) { Text("Vybrať z galérie") }
    } else Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory={preview},modifier=Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val w=if(invoice)size.width*.8f else size.width*.88f
                val h=if(invoice)minOf(w*1.414f,size.height*.85f) else minOf(w*.42f,size.height*.65f)
                val x=(size.width-w)/2;val y=(size.height-h)/2
                drawRect(Color(0x77000000),Offset.Zero,Size(size.width,y));drawRect(Color(0x77000000),Offset(0f,y+h),Size(size.width,y))
                drawRect(Color(0x77000000),Offset(0f,y),Size(x,h));drawRect(Color(0x77000000),Offset(x+w,y),Size(x,h))
                drawRect(Color.White,Offset(x,y),Size(w,h),style=Stroke(3.dp.toPx()))
            }
            Text(if(invoice) "Celá faktúra v rámčeku" else "Displej merača v rámčeku",color=Color.White,modifier=Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0x99000000)).padding(10.dp))
        }
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(warning,style=MaterialTheme.typography.bodyMedium)
            Button(enabled=!capturing && camera!=null,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),onClick={
                capturing=true
                val folder=File(context.cacheDir,"camera").apply { mkdirs() }
                folder.listFiles()?.filter { it.lastModified()<System.currentTimeMillis()-86_400_000 }?.forEach { it.delete() }
                val file=File.createTempFile("scan-",".jpg",folder)
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) { capturing=false;if(active.get())onPhoto(Uri.fromFile(file)) }
                    override fun onError(exception: ImageCaptureException) { capturing=false;if(active.get())onError("Snímku sa nepodarilo odfotiť. Skúste to znovu alebo vyberte fotografiu.") }
                })
            }) { Text(if(capturing)"Fotografujem…" else if(invoice)"Odfotiť faktúru" else "Odfotiť merač") }
            TextButton(enabled=!capturing,onClick=onGallery,modifier=Modifier.fillMaxWidth()) { Text("Vybrať z galérie") }
        }
    }
}
