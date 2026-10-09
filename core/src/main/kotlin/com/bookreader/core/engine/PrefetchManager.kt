package com.bookreader.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 预取：翻页不该等磁盘，也不该在翻页那一刻才排版。
 * 页起始表是有状态的，所以排版统一丢到一个单并行度的调度器里，和主线程靠 ReaderEngine 的锁互斥。
 */
class PrefetchManager(private val aheadPages: Int = 6) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var job: Job? = null

    fun prefetch(engine: ReaderEngine, ahead: Int = aheadPages) {
        job?.cancel()
        job = scope.launch {
            runCatching { engine.warmAhead(ahead) }
        }
    }

    fun shutdown() {
        job?.cancel()
        scope.cancel()
    }
}
