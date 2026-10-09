package com.bookreader.core.ingest

/**
 * 章节标题识别。宁可漏掉一章（读者还能往后翻），不要认错行（会把正文切成碎片）。
 *
 * 两条硬规矩，都是真实书里踩出来的：
 * 1. 标题行不许带句末标点。"第一章，十二点前上传第二章。" 这种广告行、"1.拥有5张日红票……。"
 *    这种清单行，长度和开头数字都像标题，但真标题不会出现 。！？；
 * 2. 认"数字直接接汉字"的裸编号（带玉的前 2463 章全是 001你到底是谁），否则整本书并成一章，
 *    分页代价从"一章"变成"一本书"。
 */
object ChapterPattern {

    private const val NUMBER = "0-9零〇一二三四五六七八九十百千万两亿"
    private const val CJK = "\\p{IsHan}"

    /** 句末标点一旦出现就不是标题行；逗号允许（"第一章，小二上酒" 是真写法）。 */
    private val SENTENCE_END = Regex("""[。！？；!?;]""")

    private val CN = Regex("""^第\s*[$NUMBER]{1,14}\s*[节章回卷篇部折幕](?:[ 　、，:：]?[^\s].{0,58})?$""")
    private val EN = Regex("""^(?:chapter|part|episode)\s*[0-9]{1,6}\b.{0,58}$""", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("""^[0-9]{1,5}\s*[、.．]\s*\S.{0,48}$""")
    // 裸编号只允许"数字 + 汉字/字母"，中间不许有标点：否则"1998年冬天，他……"这种正文行会被当成章
    private val BARE = Regex("""^[0-9]{1,5}$CJK[$CJK A-Za-z0-9·]{0,30}$""")
    private val SPECIAL = Regex(
        """^(?:正文|序章|序言|楔子|引子|前言|后记|尾声|番外|附录|大结局|终章|新书感言|完本感言|作品相关)""" +
            """(?:\s*[$NUMBER]{1,10})?(?:\s.*)?$"""
    )

    fun matches(line: String): Boolean {
        if (line.length !in 2..80) return false
        if (SENTENCE_END.containsMatchIn(line)) return false
        return CN.matches(line) || EN.matches(line) || NUMBERED.matches(line) || BARE.matches(line) || SPECIAL.matches(line)
    }
}
