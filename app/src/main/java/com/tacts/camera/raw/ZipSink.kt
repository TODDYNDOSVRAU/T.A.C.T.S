package com.tacts.camera.raw

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.zip.CRC32

/**
 * A ZIP written as it streams, with no compression and Zip64 records, so a RAW clip can grow past 4 GB.
 *
 * The platform's ZipOutputStream cannot do that. Entries are method 8 (deflate) holding only "stored"
 * blocks, which every reader accepts and which needs neither the size nor the checksum up front: both
 * follow the data in a descriptor. Nothing is buffered beyond one block.
 */
class ZipSink(private val out: OutputStream) {

  private class Entry(val name: ByteArray, val offset: Long, val dosTime: Int, val dosDate: Int) {
    var crc = 0L
    var compressed = 0L
    var size = 0L
  }

  private val entries = ArrayList<Entry>()
  private var position = 0L
  private var open: Entry? = null
  private var closed = false

  /** Starts an entry; write the data to the returned stream, then call [finishEntry]. */
  fun startEntry(name: String): OutputStream {
    check(open == null) { "An entry is still open" }
    val bytes = name.toByteArray(Charsets.UTF_8)
    val now = Calendar.getInstance()
    val time = (now.get(Calendar.HOUR_OF_DAY) shl 11) or (now.get(Calendar.MINUTE) shl 5) or (now.get(Calendar.SECOND) / 2)
    val date = ((now.get(Calendar.YEAR) - 1980) shl 9) or ((now.get(Calendar.MONTH) + 1) shl 5) or now.get(Calendar.DAY_OF_MONTH)
    val entry = Entry(bytes, position, time, date)
    open = entry

    val header = buffer(30 + bytes.size + 20)
    header.putInt(0x04034b50)
    header.putShort(45)
    header.putShort(FLAGS)
    header.putShort(8)
    header.putShort(time.toShort())
    header.putShort(date.toShort())
    header.putInt(0)
    header.putInt(-1)
    header.putInt(-1)
    header.putShort(bytes.size.toShort())
    header.putShort(20)
    header.put(bytes)
    header.putShort(1)
    header.putShort(16)
    header.putLong(0)
    header.putLong(0)
    emit(header)
    return stream
  }

  private val crc = CRC32()
  private val block = ByteArray(5)

  private val stream = object : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
      val entry = open ?: error("No open entry")
      var at = off
      var left = len
      while (left > 0) {
        val n = minOf(left, 65535)
        block[0] = 0
        block[1] = n.toByte()
        block[2] = (n shr 8).toByte()
        block[3] = n.inv().toByte()
        block[4] = (n.inv() shr 8).toByte()
        out.write(block)
        out.write(b, at, n)
        crc.update(b, at, n)
        position += 5 + n
        entry.compressed += 5 + n
        entry.size += n
        at += n
        left -= n
      }
    }
  }

  fun finishEntry() {
    val entry = open ?: return
    // The closing (empty, final) stored block.
    out.write(byteArrayOf(1, 0, 0, -1, -1))
    position += 5
    entry.compressed += 5
    entry.crc = crc.value
    crc.reset()
    val descriptor = buffer(24)
    descriptor.putInt(0x08074b50)
    descriptor.putInt(entry.crc.toInt())
    descriptor.putLong(entry.compressed)
    descriptor.putLong(entry.size)
    emit(descriptor)
    entries.add(entry)
    open = null
  }

  /** Writes the directory and closes the file. An entry left open is dropped. */
  fun close() {
    if (closed) return
    closed = true
    try {
      open?.let { open = null }
      val directoryStart = position
      for (e in entries) {
        val central = buffer(46 + e.name.size + 28)
        central.putInt(0x02014b50)
        central.putShort(45)
        central.putShort(45)
        central.putShort(FLAGS)
        central.putShort(8)
        central.putShort(e.dosTime.toShort())
        central.putShort(e.dosDate.toShort())
        central.putInt(e.crc.toInt())
        central.putInt(-1)
        central.putInt(-1)
        central.putShort(e.name.size.toShort())
        central.putShort(28)
        central.putShort(0)
        central.putShort(0)
        central.putShort(0)
        central.putInt(0)
        central.putInt(-1)
        central.put(e.name)
        central.putShort(1)
        central.putShort(24)
        central.putLong(e.size)
        central.putLong(e.compressed)
        central.putLong(e.offset)
        emit(central)
      }
      val directorySize = position - directoryStart

      val zip64Start = position
      val end64 = buffer(56)
      end64.putInt(0x06064b50)
      end64.putLong(44)
      end64.putShort(45)
      end64.putShort(45)
      end64.putInt(0)
      end64.putInt(0)
      end64.putLong(entries.size.toLong())
      end64.putLong(entries.size.toLong())
      end64.putLong(directorySize)
      end64.putLong(directoryStart)
      emit(end64)

      val locator = buffer(20)
      locator.putInt(0x07064b50)
      locator.putInt(0)
      locator.putLong(zip64Start)
      locator.putInt(1)
      emit(locator)

      val end = buffer(22)
      end.putInt(0x06054b50)
      end.putShort(0)
      end.putShort(0)
      end.putShort(-1)
      end.putShort(-1)
      end.putInt(-1)
      end.putInt(-1)
      end.putShort(0)
      emit(end)
      out.flush()
    } finally {
      out.close()
    }
  }

  private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

  private fun emit(b: ByteBuffer) {
    out.write(b.array(), 0, b.position())
    position += b.position()
  }

  private fun ByteBuffer.putShort(v: Int): ByteBuffer = putShort(v.toShort())

  private companion object {
    /** Bit 3: sizes and checksum follow the data. Bit 11: names are UTF-8. */
    const val FLAGS = 0x0808
  }
}
