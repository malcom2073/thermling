package com.thermling

import com.thermling.protocol.Battery
import com.thermling.protocol.Color
import com.thermling.protocol.Style
import com.thermling.protocol.Status
import com.thermling.protocol.bitmapWidthDots
import com.thermling.protocol.buildJob
import com.thermling.protocol.crc16
import com.thermling.protocol.formatNumber
import com.thermling.protocol.parseBatteryReply
import com.thermling.protocol.parseConfigReply
import com.thermling.protocol.parseLabelReply
import com.thermling.protocol.parsePrintReply
import com.thermling.protocol.parseStatusReply
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private val labelBlock = hex("000c011203000301121215280f0eed03")
    private val configPayload = hex("00cb00020204020e0201")

    @Test
    fun crc16MatchesKnownLabelVector() {
        assertEquals("ed03", crc16(labelBlock.copyOfRange(0, 14)).toHex())
    }

    @Test
    fun parsesLabelRoll() {
        val label = parseLabelReply(labelBlock)
        assertEquals(14.0, label.widthMm)
        assertEquals(40.0, label.lengthMm)
        assertEquals(5.0, label.gapMm)
        assertEquals(Style.GAPPED, label.style)
        assertEquals(Color.WHITE, label.color)
        assertEquals("011203000301121215280F0E", label.paperCode)
        assertTrue(label.hasTag)
        assertTrue(label.crcOk)
        assertEquals(0, label.status)
    }

    @Test
    fun parsesConfig() {
        val reply = "CONFIG ".encodeToByteArray() + configPayload + "\r\n".encodeToByteArray()
        val config = parseConfigReply(reply)
        assertEquals(203, config.dpi)
        assertEquals("0.2.2", config.hardwareVersion)
        assertEquals("4.2.14", config.firmwareVersion)
        assertEquals(2, config.timer)
        assertTrue(config.beep)
    }

    @Test
    fun parsesBatteryBcd() {
        val reply = "BATTERY ".encodeToByteArray() + hex("7501") + "\r\n".encodeToByteArray()
        assertEquals(Battery(75, true), parseBatteryReply(reply))
    }

    @Test
    fun parsesStatusByte() {
        assertEquals(Status.READY.code, parseStatusReply(byteArrayOf(0)))
        assertEquals(Status.NO_PAPER.code, parseStatusReply(byteArrayOf(4)))
    }

    @Test
    fun validatesPrintReplyCrc() {
        val payload = ByteArray(14)
        val reply = payload + crc16(payload)
        assertEquals(0, parsePrintReply(reply))
        assertNull(parsePrintReply(reply.copyOf().also { it[0] = 7 }))
    }

    @Test
    fun buildsJobHeaderAndFooter() {
        val bitmap = ByteArray(3) { 0xFF.toByte() }
        val job = buildJob(
            labelWidthMm = 14.0,
            labelLengthMm = 40.0,
            gapMm = 5.0,
            style = Style.GAPPED,
            bitmap = bitmap,
            widthDots = 8,
            heightDots = 3,
            density = 15,
            copies = 1,
        )
        val header = (
            "\r\nSIZE 14 mm,40 mm\r\nGAP 5 mm,0 mm\r\nDIRECTION 1,1\r\n" +
                "DENSITY 15\r\nCLS\r\nBITMAP 0,0,1,3,1,"
            ).encodeToByteArray()
        assertContentEquals(header, job.copyOfRange(0, header.size))
        val footer = "\r\nPRINT 1\r\n".encodeToByteArray()
        assertContentEquals(footer, job.copyOfRange(job.size - footer.size, job.size))
    }

    @Test
    fun geometryMatchesPrinterHead() {
        assertEquals(96, bitmapWidthDots(12.0, 203))
        assertEquals("14", formatNumber(14.0))
        assertEquals("14.5", formatNumber(14.5))
    }

    @Test
    fun bitmapWidthIsPaddedToWholeBytes() {
        assertEquals(100 * 8 / 8 * 8, bitmapWidthDots(100.0, 203))
        assertTrue(bitmapWidthDots(100.0, 203) % 8 == 0)
    }
}

private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
