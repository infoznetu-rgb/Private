package sk.preplatok.app

import android.Manifest
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CameraUiTest {
    @get:Rule val permission: GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val compose=createComposeRule()
    @Test fun guidedCameraCapturesAnActualEmulatorFrame() {
        val photo=java.util.concurrent.atomic.AtomicReference<Uri?>(null)
        val failure=java.util.concurrent.atomic.AtomicReference<String?>(null)
        compose.setContent { MaterialTheme { CameraScreen(false,onPhoto={photo.set(it)},onGallery={},onError={failure.set(it)}) } }
        compose.onNodeWithText("Displej merača v rámčeku").assertExists()
        compose.waitUntil(25000) { failure.get()!=null || compose.onAllNodes(hasText("Odfotiť merač") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertNull(failure.get())
        compose.onNodeWithText("Odfotiť merač").performClick()
        compose.waitUntil(25000) { photo.get()!=null || failure.get()!=null }
        assertNull(failure.get());assertNotNull(photo.get())
        val file=File(photo.get()!!.path!!);assertTrue(file.length()>100)
        val dimensions=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeFile(file.path,dimensions)
        assertTrue(dimensions.outWidth>0 && dimensions.outHeight>0)
        file.delete()
    }
}
