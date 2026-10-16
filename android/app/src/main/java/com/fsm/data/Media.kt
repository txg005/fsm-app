package com.fsm.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.fsm.sync.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume

object Media {
    val filesMutex=Mutex()
    suspend fun importPhoto(context:Context,repo:Repository,orderId:String,uri:Uri) = filesMutex.withLock { withContext(Dispatchers.IO) {
        // Decode bounds first: never load an unbounded picker image into the heap. Re-encode removes EXIF.
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) }
        require(options.outWidth>0 && options.outHeight>0) { "Не удалось прочитать фото" }
        options.inSampleSize=1
        while(options.outWidth/options.inSampleSize>2048 || options.outHeight/options.inSampleSize>2048) options.inSampleSize*=2
        options.inJustDecodeBounds=false
        val bitmap=context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) } ?: error("Не удалось прочитать фото")
        val orientation=runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                androidx.exifinterface.media.ExifInterface(requireNotNull(input)).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)
            }
        }.getOrDefault(1)
        val matrix=android.graphics.Matrix()
        val transform=when(orientation) {
            2 -> floatArrayOf(-1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
            3 -> floatArrayOf(-1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
            4 -> floatArrayOf(1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
            5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
            6 -> floatArrayOf(0f,-1f,0f, 1f,0f,0f, 0f,0f,1f)
            7 -> floatArrayOf(0f,-1f,0f, -1f,0f,0f, 0f,0f,1f)
            8 -> floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
            else -> null
        }
        val corrected=if(transform!=null) {
            matrix.setValues(transform)
            Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
        } else bitmap
        val id=uuid(); val temp=File(repo.files,"$id.tmp"); val target=File(repo.files,"$id.jpg")
        try {
            java.io.FileOutputStream(temp).use { output -> check(corrected.compress(Bitmap.CompressFormat.JPEG,85,output)); output.fd.sync() }
            check(temp.renameTo(target)); val hash=sha256(target.readBytes())
            repo.addPhoto(orderId,target,id,hash)
        } finally { if(corrected!==bitmap) corrected.recycle(); bitmap.recycle(); temp.delete() }
    } }
    suspend fun location(context:Context):LocationDto {
        val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) return LocationDto.missing("DENIED")
        val manager=context.getSystemService(LocationManager::class.java)
        val providers=manager.getProviders(true).filter { it==LocationManager.GPS_PROVIDER && fine || it==LocationManager.NETWORK_PROVIDER }
        if(providers.isEmpty()) return LocationDto.missing("PROVIDER_DISABLED")
        return try {
            withTimeoutOrNull(15000) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val start=android.os.SystemClock.elapsedRealtimeNanos()
                        val listener=object:LocationListener {
                            override fun onLocationChanged(l:Location) {
                                if(l.elapsedRealtimeNanos<start || !l.hasAccuracy() || !l.latitude.isFinite() || !l.longitude.isFinite()) return
                                if(cont.isActive) { manager.removeUpdates(this); cont.resume(LocationDto("CAPTURED",null,l.latitude,l.longitude,l.accuracy.toDouble(),instant(l.time))) }
                            }
                            override fun onProviderEnabled(provider:String) {}
                            override fun onProviderDisabled(provider:String) {}
                            @Deprecated("Legacy") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
                        }
                        cont.invokeOnCancellation { manager.removeUpdates(listener) }
                        try { providers.forEach { manager.requestLocationUpdates(it,0L,0f,listener,Looper.getMainLooper()) } }
                        catch(_:SecurityException) { manager.removeUpdates(listener); if(cont.isActive) cont.resume(LocationDto.missing("DENIED")) }
                    }
                }
            } ?: LocationDto.missing("TIMEOUT")
        } catch(e:CancellationException) { throw e } catch(_:Exception) { LocationDto.missing("UNAVAILABLE") }
    }
}
