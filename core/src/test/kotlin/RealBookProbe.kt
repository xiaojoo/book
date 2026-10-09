package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.storage.Book
import org.junit.Test
import java.io.File

/** 临时探针：真实小说入库。跑完就删。 */
class RealBookProbe {

    @Test
    fun ingestRealNovels() {
        for (path in listOf("G:/小说/雪中悍刀行.txt", "G:/小说/剑来.txt", "G:/小说/带玉.txt")) {
            val src = File(path)
            if (!src.exists()) {
                println("[real] 跳过（没有这个文件）$path")
                continue
            }
            val dir = File(Fixtures.tempRoot("real"), src.nameWithoutExtension)
            val t0 = System.nanoTime()
            val report = TxtIngestor().ingest(src, dir, title = src.nameWithoutExtension)
            val ingestMs = (System.nanoTime() - t0) / 1_000_000
            val meta = report.meta
            val book = Book.open(dir, 6)
            val lens = book.chapters.chapters.map { it.charCount }
            println(
                "[real] ${src.name} 源=${src.length()} bytes 编码=${report.charset} 字=${meta.totalChars} " +
                    "块=${meta.chunkCount} 章=${meta.chapterCount} 标题命中=${report.chapterTitlesFound} 入库=${ingestMs}ms"
            )
            println(
                "[real]   章长 平均=${lens.average().toLong()} 最短=${lens.min()} 最长=${lens.max()} " +
                    "短于40字的章=${lens.count { it < 40 }}"
            )
            println("[real]   首章=${book.chapters[0].title} 第2章=${if (lens.size > 1) book.chapters[1].title else "-"} 末章=${book.chapters.chapters.last().title}")
            println("[real]   开头80字=${book.cache.slice(0L, 80).replace('\n', '|')}")

            val e = ReaderEngine(book, ReaderEngine.approximateBreaker(), Fixtures.STYLE)
            val mid = book.chapters.size / 2
            val tJump = System.nanoTime()
            e.jumpToChapter(mid)
            val jumpMs = (System.nanoTime() - tJump) / 1_000_000
            val tTurn = System.nanoTime()
            var turns = 0
            while (turns < 500 && e.nextPage() != null) turns++
            val turnMs = (System.nanoTime() - tTurn) / 1_000_000
            val s = e.stats()
            println(
                "[real]   跳第${mid}章=${jumpMs}ms 翻${turns}页=${turnMs}ms(每页 %.2fms) 常驻块=${s.residentChunks} "
                    .format(turnMs.toDouble() / turns.coerceAtLeast(1)) +
                    "读盘=${s.decodedBytes}/${meta.contentBytes} 命中=${s.chunkHits}/${s.chunkMisses}"
            )
            e.close()
        }
    }
}
