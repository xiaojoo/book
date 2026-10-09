package com.bookreader.core

import com.bookreader.core.text.safeConvert
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 繁简转换唯一的不变量是"长度不变"：页上的行是按源文偏移切的，长度一变行就切错。
 * 所以这段判据要能在转换器改长度、抛异常时都退回原文。
 */
class TextConvertTest {

    @Test
    fun lengthPreservingConversionIsApplied() {
        val out = safeConvert("風雲變色") { it.map { c -> map[c] ?: c }.joinToString("") }
        assertEquals("风云变色", out)
    }

    @Test
    fun conversionThatChangesLengthFallsBackToSource() {
        // 一个字被换成两个字：整段必须原样退回，不能只丢那一格
        val out = safeConvert("風雲") { "风风云" }
        assertEquals("風雲", out)
    }

    @Test
    fun throwingConverterFallsBackToSource() {
        val out = safeConvert("風雲") { error("ICU 没装") }
        assertEquals("風雲", out)
    }

    @Test
    fun emptySliceIsPassedThroughUnconverted() {
        var called = 0
        assertEquals("", safeConvert("") { called++; "x" })
        assertEquals("空段不该惊动转换器", 0, called)
    }

    private val map = mapOf('風' to '风', '雲' to '云', '變' to '变', '色' to '色')
}
