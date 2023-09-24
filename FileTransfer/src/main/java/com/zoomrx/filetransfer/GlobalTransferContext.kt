package com.zoomrx.filetransfer

import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

open class GlobalTransferContext {
    var prevTransferSpeed = 0F
    var currTransferSpeed = 1F
    var activeTransfers: MutableList<TransferContext> = Collections.synchronizedList(arrayListOf<TransferContext>())
    var queuedTransfers = ConcurrentLinkedQueue<TransferContext>()
    var allowNewTransfer = AtomicBoolean(true)
    open fun startTransfer(transferContext: TransferContext) {}
}