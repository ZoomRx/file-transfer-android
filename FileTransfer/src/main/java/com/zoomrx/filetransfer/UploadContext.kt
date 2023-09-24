package com.zoomrx.filetransfer

import okhttp3.Call
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class UploadContext(
        override val source: String,
        override val destination: String,
        override val headers: JSONObject?,
        override val progressListener: ((Long, Long) -> Unit)?,
        override val successCallback: (JSONObject) -> Unit,
        override val errorCallback: (JSONObject) -> Unit,
        override val backgroundMode: Boolean,
        val fileKey: String,
        val fileName: String,
        val mimeType: String
) : TransferContext {
    override val globalTransferContext
        get() = FileTransferHandler.globalUploadContext
    override var transferId: Int = 0
    override var abort: Boolean = false
    override val queuedTime: Long = System.currentTimeMillis()
    override var startTime = 0L
    override var endTime = 0L
    override var lastKnownTimeStamp = 0L
    override var lastKnownBytesTransferred = 0L
    override var speedHistoryQueue = ConcurrentLinkedQueue<FileTransferHandler.SpeedQueueElement>()
    override val speedHistoryQueueSize = 150
    override var speed: Float = 0F
    override var bytesTransferred = 0L
    override var totalBytes = 0L
    lateinit var call: Call
}