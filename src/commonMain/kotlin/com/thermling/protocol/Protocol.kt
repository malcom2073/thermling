package com.thermling.protocol

import kotlin.math.max
import kotlin.math.roundToInt

const val BAUD = 115200
const val DEFAULT_DEVICE = "/dev/rfcomm0"
const val DEFAULT_DPI = 203
const val DEFAULT_HEAD_WIDTH_MM = 12.0

val CMD_CONFIG: ByteArray = "CONFIG?\r\n".encodeToByteArray()
val CMD_BATTERY: ByteArray = "BATTERY?\r\n".encodeToByteArray()
val CMD_STATUS: ByteArray = byteArrayOf(0x1B, 0x21, 0x3F, 0x0D, 0x0A)
val CMD_LABEL: ByteArray = byteArrayOf(0x1B, 0x21, 0x6F, 0x0D, 0x0A)
val CMD_SELFTEST: ByteArray = "SELFTEST\r\n".encodeToByteArray()

class ProtocolException(message: String) : Exception(message)

enum class Status(val code: Int, val label: String) {
    READY(0x00, "Ready"),
    LID_OPEN(0x01, "Lid Open"),
    FEEDING(0x02, "Feeding"),
    PAPER_ERROR(0x03, "Paper Error"),
    NO_PAPER(0x04, "No Paper"),
    PAUSED(0x10, "Paused"),
    BUSY(0x20, "Busy"),
    OUT_OF_PAPER(0x24, "Out Of Paper"),
    LOW_BATTERY(0x30, "Low Battery"),
    OVERHEATED(0x80, "Overheated"),
    NO_POWER(0x90, "No Power");

    companion object {
        fun fromCode(code: Int): Status? = entries.firstOrNull { it.code == code }
    }
}

enum class Color(val code: Int, val label: String) {
    UNKNOWN(0, "Unknown"),
    TRANSPARENT(2, "Transparent"),
    WHITE(3, "White"),
    PINK(4, "Pink"),
    BLUE(5, "Blue"),
    YELLOW(6, "Yellow");

    companion object {
        fun fromCode(code: Int): Color = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class Style(val code: Int, val label: String) {
    CONTINUOUS(0, "Continuous"),
    GAPPED(1, "Gapped"),
    BLACKMARK(2, "Blackmark");

    companion object {
        fun fromCode(code: Int): Style = entries.firstOrNull { it.code == code } ?: CONTINUOUS
    }
}

val TIMER_TEXT = mapOf(0 to "Never", 1 to "15 minutes", 2 to "30 minutes", 3 to "60 minutes")
val MINUTES_TO_TIMER = mapOf(0 to 0, 15 to 1, 30 to 2, 60 to 3)

fun describeStatus(code: Int?): String {
    if (code == null) return "Unknown"
    return Status.fromCode(code)?.label ?: "Unknown (0x${code.toString(16).padStart(2, '0')})"
}

data class Config(
    val dpi: Int,
    val hardwareVersion: String,
    val firmwareVersion: String,
    val timer: Int,
    val beep: Boolean,
) {
    val timerText: String get() = TIMER_TEXT[timer] ?: timer.toString()

    override fun toString(): String =
        "$dpi dpi, firmware $firmwareVersion · auto-off $timerText · beep ${if (beep) "on" else "off"}"
}

data class Battery(val percent: Int, val charging: Boolean) {
    override fun toString(): String = "$percent%${if (charging) " (charging)" else ""}"
}

data class LabelRoll(
    val widthMm: Double,
    val lengthMm: Double,
    val gapMm: Double,
    val color: Color,
    val style: Style,
    val paperCode: String,
    val status: Int = 0,
    val crcOk: Boolean = true,
) {
    val hasTag: Boolean get() = paperCode.isNotEmpty()

    fun describe(): String {
        if (!hasTag) return "No readable label tag"
        return "${formatNumber(widthMm)} × ${formatNumber(lengthMm)} mm · ${style.label} · ${color.label} · gap ${formatNumber(gapMm)} mm"
    }
}

fun crc16(data: ByteArray): ByteArray {
    var crc = 0xFFFF
    for (byte in data) {
        crc = crc xor (byte.toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
        }
    }
    return byteArrayOf(((crc ushr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
}

private fun payload(reply: ByteArray, prefix: ByteArray, minimum: Int): ByteArray {
    if (!reply.startsWith(prefix)) {
        throw ProtocolException("unexpected reply: ${reply.decodeToString()}")
    }
    var body = reply.copyOfRange(prefix.size, reply.size)
    if (body.size >= 2 && body[body.size - 2] == 0x0D.toByte() && body[body.size - 1] == 0x0A.toByte()) {
        body = body.copyOfRange(0, body.size - 2)
    }
    if (body.size < minimum) {
        throw ProtocolException("short reply: ${reply.decodeToString()}")
    }
    return body
}

fun parseConfigReply(reply: ByteArray): Config {
    val body = payload(reply, "CONFIG ".encodeToByteArray(), 10)
    val dpi = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
    if (dpi !in 50..1200) {
        throw ProtocolException("nonsensical DPI in CONFIG reply: $dpi")
    }
    return Config(
        dpi = dpi,
        hardwareVersion = "${body[2].toInt() and 0xFF}.${body[3].toInt() and 0xFF}.${body[4].toInt() and 0xFF}",
        firmwareVersion = "${body[5].toInt() and 0xFF}.${body[6].toInt() and 0xFF}.${body[7].toInt() and 0xFF}",
        timer = body[8].toInt() and 0xFF,
        beep = (body[9].toInt() and 0xFF) != 0,
    )
}

fun parseBatteryReply(reply: ByteArray): Battery {
    val body = payload(reply, "BATTERY ".encodeToByteArray(), 2)
    val raw = body[0].toInt() and 0xFF
    val level = ((raw ushr 4) and 0x0F) * 10 + (raw and 0x0F)
    return Battery(percent = level, charging = (body[1].toInt() and 0xFF) != 0)
}

fun parseStatusReply(reply: ByteArray): Int {
    if (reply.isEmpty()) throw ProtocolException("empty status reply")
    return reply[0].toInt() and 0xFF
}

fun parseLabelReply(reply: ByteArray): LabelRoll {
    if (reply.size != 16) {
        throw ProtocolException("expected a 16-byte label block, got ${reply.size} bytes")
    }
    val crcOk = crc16(reply.copyOfRange(0, 14)).contentEquals(reply.copyOfRange(14, 16))
    val format = (reply[3].toInt() and 0xFF) ushr 4
    val styleByte = if (format >= 2) reply[7].toInt() and 0x0F else reply[7].toInt() and 0xFF
    val tag = reply.copyOfRange(2, 14)
    val gapByte = reply[8].toInt() and 0xFF
    return LabelRoll(
        widthMm = (reply[13].toInt() and 0xFF).toDouble(),
        lengthMm = (reply[11].toInt() and 0xFF).toDouble(),
        gapMm = if (format >= 2 && gapByte != 0) gapByte.toDouble() else 5.0,
        color = Color.fromCode(reply[4].toInt() and 0xFF),
        style = Style.fromCode(styleByte),
        paperCode = if (tag.any { it.toInt() != 0 }) tag.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase() else "",
        status = reply[0].toInt() and 0xFF,
        crcOk = crcOk,
    )
}

fun parsePrintReply(reply: ByteArray): Int? {
    if (reply.size != 16) return null
    val crcOk = crc16(reply.copyOfRange(0, 14)).contentEquals(reply.copyOfRange(14, 16))
    if (!crcOk) return null
    return reply[0].toInt() and 0xFF
}

fun mmToDots(mm: Double, dpi: Int): Double = mm / 25.4 * dpi

fun dotsToMm(dots: Double, dpi: Int): Double = dots * 25.4 / dpi

fun bitmapWidthDots(printableWidthMm: Double, dpi: Int): Int =
    max(1, (mmToDots(printableWidthMm, dpi) / 8.0).roundToInt()) * 8

fun printableWidthMm(labelWidthMm: Double, headWidthMm: Double = DEFAULT_HEAD_WIDTH_MM): Double =
    max(1.0, minOf(labelWidthMm, headWidthMm))

fun buildJob(
    labelWidthMm: Double,
    labelLengthMm: Double,
    gapMm: Double,
    style: Style,
    bitmap: ByteArray,
    widthDots: Int,
    heightDots: Int,
    density: Int = 15,
    copies: Int = 1,
    direction: Pair<Int, Int> = 1 to 1,
): ByteArray {
    require(widthDots > 0 && widthDots % 8 == 0) { "bitmap width must be a positive whole number of bytes" }
    require(heightDots > 0) { "bitmap height must be positive" }
    require(labelWidthMm > 0 && labelLengthMm > 0) { "label size must be positive" }
    require(density in 1..15) { "density must be between 1 and 15" }
    require(copies >= 1) { "copies must be at least 1" }
    val rowBytes = widthDots / 8
    require(bitmap.size == rowBytes * heightDots) {
        "bitmap is ${bitmap.size} bytes, expected ${rowBytes * heightDots} ($rowBytes bytes × $heightDots rows)"
    }
    val gap = if (style == Style.GAPPED) gapMm else 0.0
    val header = buildString {
        append("\r\nSIZE ${formatNumber(labelWidthMm)} mm,${formatNumber(labelLengthMm)} mm\r\n")
        append("GAP ${formatNumber(gap)} mm,0 mm\r\n")
        append("DIRECTION ${direction.first},${direction.second}\r\n")
        append("DENSITY $density\r\n")
        append("CLS\r\n")
        append("BITMAP 0,0,$rowBytes,$heightDots,1,")
    }.encodeToByteArray()
    val footer = "\r\nPRINT $copies\r\n".encodeToByteArray()
    return header + bitmap + footer
}

fun formatNumber(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return value.toString().trimEnd('0').trimEnd('.')
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}
