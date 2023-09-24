package com.zoomrx.filetransfer

import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

interface TransferContext {
    val globalTransferContext: GlobalTransferContext
    var transferId: Int
    val source: String
    val destination: String
    val headers: JSONObject?
    val progressListener: ((Long, Long) -> Unit)?
    val successCallback: (JSONObject) -> Unit
    val errorCallback: (JSONObject) -> Unit
    val backgroundMode: Boolean
    var abort: Boolean
    var lastKnownBytesTransferred: Long
    var lastKnownTimeStamp: Long
    var bytesTransferred: Long
    var totalBytes: Long
    val queuedTime: Long
    var startTime: Long
    var endTime: Long
    var speedHistoryQueue: ConcurrentLinkedQueue<FileTransferHandler.SpeedQueueElement>
    var speed: Float
    val speedHistoryQueueSize: Int
}