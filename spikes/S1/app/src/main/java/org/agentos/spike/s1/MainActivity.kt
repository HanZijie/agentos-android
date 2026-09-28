package org.agentos.spike.s1

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import java.security.MessageDigest

/** Shows which build is installed: versionCode, versionName and the signing certificate digest. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val digests = info.signingInfo?.apkContentsSigners.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        setContentView(TextView(this).apply {
            setPadding(32, 32, 32, 32)
            text = "versionCode=${info.longVersionCode}\nversionName=${info.versionName}\n" +
                "installer=${packageManager.getInstallSourceInfo(packageName).installingPackageName}\n" +
                "cert sha256=${digests.joinToString()}"
        })
    }
}
