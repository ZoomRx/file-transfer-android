package com.zoomrx.filetransfer

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okio.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.IllegalArgumentException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object FileTransferHandler {

    private const val BUFFER_SIZE = 8 * 1024
    private const val REQUEST_TIMEOUT = 30L
    private const val SOCKET_TIMEOUT = 8 * 1024

    object ErrorCode {
        const val INSUFFICIENT_DATA = 1
        const val FILE_NOT_FOUND = 2
        const val REQUEST_FAILURE = 3
        const val DUPLICATE_FOUND = 4
        const val ABORTED = 5
    }

    object ErrorMessage {
        const val INSUFFICIENT_DATA = "Insufficient data"
        const val FILE_NOT_FOUND = "File not found"
        const val REQUEST_FAILURE = "Request failure"
        const val DUPLICATE_FOUND = "Duplicate request found"
        const val ABORTED = "Transfer aborted"
    }

    data class SpeedQueueElement(val readBytes: Long, val timeTaken: Long)

    private var currTransferIndex = AtomicInteger(0)
    private val transferList: MutableList<TransferContext> = Collections.synchronizedList(arrayListOf<TransferContext>())
    val globalDownloadContext = object : GlobalTransferContext() {
        override fun startTransfer(transferContext: TransferContext) {
            super.startTransfer(transferContext)
            startDownload(transferContext as DownloadContext)
        }
    }

    val globalUploadContext = object : GlobalTransferContext() {
        override fun startTransfer(transferContext: TransferContext) {
            super.startTransfer(transferContext)
            startUpload(transferContext as UploadContext)
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

    fun onActivityDestroy() {
        transferList.forEach {
            it.abort = true
        }
    }

    fun abortTransfer(transferId: Int): Boolean {
        return transferList.find {
            it.transferId == transferId
        }?.run {
            abort = true
            true
        } ?: false
    }

    private fun checkForDuplicate(transferContext: TransferContext): Boolean {
        val duplicateSources = transferList.filter {
            it.source == transferContext.source && it.destination == transferContext.destination
        }
        return if (duplicateSources.isNotEmpty()) {
            transferContext.errorCallback(
                    JSONObject()
                            .put("message", ErrorMessage.DUPLICATE_FOUND)
                            .put("code", ErrorCode.DUPLICATE_FOUND)
            )
            true
        } else false
    }

    private fun getRecentSpeedAverage(transferContext: TransferContext): Float {
        var recentAverageSpeed = 0F
        val currTime = System.currentTimeMillis()
        transferContext.globalTransferContext.activeTransfers.forEach {
            var recentlyTransferredBytes = 0L
            var recentlyTakenTime = 0L
            if (it.lastKnownTimeStamp == 0L) {
                recentlyTransferredBytes += it.bytesTransferred
                recentlyTakenTime += currTime - it.startTime
                it.lastKnownBytesTransferred += recentlyTransferredBytes
                it.lastKnownTimeStamp = currTime
            } else {
                recentlyTransferredBytes += it.bytesTransferred - it.lastKnownBytesTransferred
                recentlyTakenTime += currTime - it.lastKnownTimeStamp
                it.lastKnownBytesTransferred += recentlyTransferredBytes
                it.lastKnownTimeStamp = currTime
            }
            recentAverageSpeed += (recentlyTransferredBytes / recentlyTakenTime).toFloat()
        }
        return recentAverageSpeed
    }

    @Synchronized
    fun onNewTransferSpeedCalculated(transferContext: TransferContext) {
        with(transferContext.globalTransferContext) {
            prevTransferSpeed = currTransferSpeed
            currTransferSpeed = getRecentSpeedAverage(transferContext)
            /*currTransferSpeed = 0F
            activeTransfers.forEach {
                currTransferSpeed += it.speed
            }*/
            Log.d("file-transfer-debug", "prev: $prevTransferSpeed, curr: $currTransferSpeed")
            if (currTransferSpeed > prevTransferSpeed * 1.4) {
                allowNewTransfer.set(true)
                if (queuedTransfers.isNotEmpty()) {
                    startTransfer(queuedTransfers.first())
                    queuedTransfers.remove(queuedTransfers.first())
                    allowNewTransfer.set(false)
                }
            } else {
                allowNewTransfer.set(false)
            }
        }
    }

    @Synchronized
    fun onTransferEnded(transferContext: TransferContext) {
        CoroutineScope(Dispatchers.Default).launch {
            with(transferContext.globalTransferContext) {
                allowNewTransfer.set(true)
                prevTransferSpeed = currTransferSpeed
                currTransferSpeed = getRecentSpeedAverage(transferContext)

                if (activeTransfers.size != 0) {
                    delay(500)
                    prevTransferSpeed = currTransferSpeed
                    currTransferSpeed = getRecentSpeedAverage(transferContext)
                    /*currTransferSpeed = 0F
                    activeTransfers.forEach {
                        currTransferSpeed += it.speed
                    }*/
                    Log.d("file-transfer-debug", "onEnd prev: $prevTransferSpeed, curr: $currTransferSpeed")
                    if (queuedTransfers.isNotEmpty()) {
                        startTransfer(queuedTransfers.first())
                        queuedTransfers.remove(queuedTransfers.first())
                        allowNewTransfer.set(false)
                    }
                } else {
                    if (queuedTransfers.isNotEmpty()) {
                        startTransfer(queuedTransfers.first())
                        queuedTransfers.remove(queuedTransfers.first())
                        allowNewTransfer.set(false)
                    }
                }
            }
        }
    }

    @Synchronized
    fun enqueueDownload(downloadContext: DownloadContext, startImmediately: Boolean = false): Int {
        if (checkForDuplicate(downloadContext)) return -1
        val fileName = downloadContext.destination.substring(downloadContext.destination.lastIndexOf('/') + 1)
        downloadContext.transferId = currTransferIndex.getAndIncrement()
        with(downloadContext.globalTransferContext) {
            if (startImmediately) {
                startTransfer(downloadContext)
            } else if (allowNewTransfer.get()) {
                startTransfer(downloadContext)
                allowNewTransfer.set(false)
            } else {
                Log.d("file-transfer", "$fileName download queued")
                queuedTransfers.add(downloadContext)
            }
        }
        return downloadContext.transferId
    }

    @Synchronized
    fun enqueueUpload(uploadContext: UploadContext): Int {
        if (checkForDuplicate(uploadContext)) return -1
        val fileName = uploadContext.destination.substring(uploadContext.destination.lastIndexOf('/') + 1)
        uploadContext.transferId = currTransferIndex.getAndIncrement()
        with(uploadContext.globalTransferContext) {
            if (allowNewTransfer.get()) {
                startTransfer(uploadContext)
                allowNewTransfer.set(false)
            } else {
                Log.d("file-transfer", "$fileName download queued")
                queuedTransfers.add(uploadContext)
            }
        }
        return uploadContext.transferId
    }

    fun startDownload(downloadContext: DownloadContext) {
        val fileName = downloadContext.destination.substring(downloadContext.destination.lastIndexOf('/') + 1)
        Log.d("file-transfer", "$fileName download initiated")
        downloadContext.globalTransferContext.activeTransfers.add(downloadContext)
        transferList.add(downloadContext)

        CoroutineScope(Dispatchers.IO).launch {
            val result = JSONObject()
            val downloadFailedWithError = { ->
                synchronized(downloadContext.globalTransferContext) {
                    downloadContext.globalTransferContext.activeTransfers.remove(downloadContext)
                    downloadContext.globalTransferContext.queuedTransfers.remove(downloadContext)
                }
                transferList.remove(downloadContext)
                downloadContext.errorCallback(result)
                downloadContext.destinationFile?.let {
                    if (it.exists())
                        it.delete()
                }
                downloadContext.endTime = System.currentTimeMillis()
                onTransferEnded(downloadContext)
            }
            try {
                val requestBuilder: Request.Builder = Request.Builder()
                        .url(downloadContext.source)
                downloadContext.headers?.keys()?.forEach { key ->
                    requestBuilder.addHeader(key, downloadContext.headers.getString(key))
                }
                val request = requestBuilder.build()
                val tempFileName = "FT_download_${downloadContext.transferId}"
                val call = okHttpClient.newCall(request)

                val abortIfRequired = {
                    if (downloadContext.abort) {
                        call.cancel()
                        result
                                .put("message", ErrorMessage.ABORTED)
                                .put("code", ErrorCode.ABORTED)
                        downloadFailedWithError()
                        true
                    } else {
                        false
                    }
                }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        result.put("message", e.message)
                                .put("code", ErrorCode.REQUEST_FAILURE)
                        downloadFailedWithError()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            result.put("message", response.message)
                                    .put("code", response.code)
                            downloadFailedWithError()
                        } else {
                            Log.d("file-transfer", "$fileName Request completed")
                            var inputStream: InputStream? = null
                            val buf = ByteArray(BUFFER_SIZE)
                            var len = 0
                            var fos: FileOutputStream? = null
                            try {
                                downloadContext.totalBytes = response.body!!.contentLength()
                                inputStream = response.body!!.byteStream()
                                val dir = File(downloadContext.destination.substring(0, downloadContext.destination.lastIndexOf('/') + 1))
                                if (!dir.exists()) {
                                    dir.mkdirs()
                                }
                                Log.d("file-transfer", "$fileName Download started")
                                val destinationFile = File(dir, tempFileName)
                                downloadContext.destinationFile = destinationFile
                                fos = FileOutputStream(destinationFile)
                                var currTime = System.currentTimeMillis()
                                downloadContext.startTime = currTime
                                var lastRecordedTime = currTime
                                var cumulativeReadBytes = 0L
                                var cumulativeTimeTaken = 0L
                                var recentTimeTaken: Long
                                var recentlyReadBytes = 0L
                                var initialSpeedCalculated = false
                                while (!abortIfRequired() && inputStream.read(buf).also { len = it } != -1) {
                                    fos.write(buf, 0, len)
                                    recentlyReadBytes += len
                                    currTime = System.currentTimeMillis()
                                    if (currTime != lastRecordedTime) {
                                        recentTimeTaken = currTime - lastRecordedTime
                                        if (downloadContext.speedHistoryQueue.size == downloadContext.speedHistoryQueueSize) {
                                            cumulativeReadBytes -= downloadContext.speedHistoryQueue.first().readBytes
                                            cumulativeTimeTaken -= downloadContext.speedHistoryQueue.first().timeTaken
                                            downloadContext.speedHistoryQueue.remove(downloadContext.speedHistoryQueue.first())
                                        }
                                        cumulativeReadBytes += recentlyReadBytes
                                        cumulativeTimeTaken += recentTimeTaken
                                        downloadContext.speedHistoryQueue.add(SpeedQueueElement(recentlyReadBytes, recentTimeTaken))
                                        downloadContext.speed = (cumulativeReadBytes / cumulativeTimeTaken).toFloat()
                                        lastRecordedTime = currTime
                                        recentlyReadBytes = 0
                                    }
                                    if (currTime - downloadContext.startTime > 1000 && !initialSpeedCalculated) {
                                        initialSpeedCalculated = true
                                        onNewTransferSpeedCalculated(downloadContext)
                                    }
                                    downloadContext.bytesTransferred += len
                                    downloadContext.progressListener?.let { it(downloadContext.bytesTransferred, downloadContext.totalBytes) }
                                }
                                fos.flush()
                            } catch (e: IOException) {
                                e.printStackTrace()
                                result.put("message", e.message)
                                        .put("code", ErrorCode.FILE_NOT_FOUND)
                                downloadFailedWithError()
                            } finally {
                                try {
                                    inputStream?.close()
                                    fos?.close()
                                    response.body?.close()
                                    if (len == -1) {
                                        val renamedFile = File(downloadContext.destinationFile?.parentFile, fileName)
                                        downloadContext.destinationFile?.renameTo(renamedFile)
                                        downloadContext.destinationFile = renamedFile
                                        Log.d("file-transfer-plugin", "$fileName downloaded")
                                        downloadContext.globalTransferContext.activeTransfers.remove(downloadContext)
                                        transferList.remove(downloadContext)
                                        downloadContext.successCallback(result)
                                        downloadContext.endTime = System.currentTimeMillis()
                                        Log.d("file-transfer stats", "Avg speed: ${downloadContext.bytesTransferred/(downloadContext.endTime - downloadContext.startTime)}")
                                        Log.d("file-transfer stats", "Time taken ${downloadContext.endTime - downloadContext.startTime}")
                                        Log.d("file-transfer stats", "Time including queuedTime ${downloadContext.endTime - downloadContext.queuedTime}")
                                        onTransferEnded(downloadContext)
                                    }
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                })
            } catch (ex: IllegalArgumentException) {
                ex.printStackTrace()
                result.put("message", ErrorMessage.INSUFFICIENT_DATA)
                        .put("code", ErrorCode.INSUFFICIENT_DATA)
                downloadFailedWithError()
            }
        }
    }

    fun startUpload(uploadContext: UploadContext): Int {
        val result = JSONObject()
        if (checkForDuplicate(uploadContext)) return -1
        val transferIndex = currTransferIndex.getAndIncrement()
        transferList.add(uploadContext)

        val uploadFailedWithError = {
            transferList.remove(uploadContext)
            uploadContext.errorCallback(result)
        }

        CoroutineScope(Dispatchers.IO).launch {

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(uploadContext.fileKey, uploadContext.fileName,
                    File(uploadContext.source).asRequestBody(uploadContext.mimeType.toMediaType()))
                .build()

            val requestBuilder: Request.Builder = Request.Builder()
                .url(uploadContext.destination)
            uploadContext.headers?.keys()?.forEach { key ->
                requestBuilder.addHeader(key, uploadContext.headers.getString(key))
            }

            val progressListener: ProgressListener = object : ProgressListener {
                var firstUpdate = true
                override fun update(bytesTransferred: Long, contentLength: Long, done: Boolean) {
                    if (uploadContext.abort) {
                        uploadContext.call.cancel()
                        result.put("message", ErrorMessage.ABORTED)
                                .put("code", ErrorCode.ABORTED)
                        uploadFailedWithError()
                        return
                    }
                    uploadContext.bytesTransferred = bytesTransferred
                    if (done) {
                        transferList.remove(uploadContext)
                        uploadContext.successCallback(result)
                    } else {
                        if (firstUpdate) {
                            firstUpdate = false
                            uploadContext.totalBytes = contentLength
                        }
                        uploadContext.progressListener?.let { it(bytesTransferred, contentLength) }
                    }
                }
            }

            requestBuilder.post(ProgressRequestBody(requestBody, progressListener))
            val request = requestBuilder.build()

            uploadContext.call = okHttpClient.newCall(request)

            try {
                uploadContext.call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        result.put("message", e.message)
                                .put("code", ErrorCode.REQUEST_FAILURE)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            result.put("message", response.message)
                                    .put("code", response.code)
                            uploadFailedWithError()
                        }
                        response.close()
                        response.body?.close()
                    }
                })
            } catch (exception: IOException) {
                exception.printStackTrace()
                result
                    .put("message", exception.message)
                    .put("code", ErrorCode.FILE_NOT_FOUND)
                uploadFailedWithError()
            } catch (ex: IllegalArgumentException) {
                ex.printStackTrace()
                result
                        .put("message", ex.message)
                        .put("code", ErrorCode.INSUFFICIENT_DATA)
                uploadFailedWithError()
            }
        }
        return transferIndex
    }

    private class ProgressRequestBody(
        private val requestBody: RequestBody,
        private val uploadProgressListener: ProgressListener
    ) : RequestBody() {
        private var bufferedSink: BufferedSink? = null
        override fun contentType(): MediaType? {
            return requestBody.contentType()
        }

        override fun writeTo(sink: BufferedSink) {
            if (bufferedSink == null) {
                bufferedSink = customSink(sink).buffer()
            }
            requestBody.writeTo(bufferedSink!!)
            bufferedSink!!.flush()
        }

        private fun customSink(sink: Sink): Sink {
            return object : ForwardingSink(sink) {
                var bytesWritten = 0L

                @Throws(IOException::class)
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    bytesWritten += byteCount
                    uploadProgressListener.update(
                        bytesWritten,
                        requestBody.contentLength(),
                        byteCount == -1L || byteCount == 0L
                    )
                }
            }
        }
    }

    internal interface ProgressListener {
        fun update(bytesTransferred: Long, contentLength: Long, done: Boolean)
    }
}