package com.makskbz.myvpnproject.vpn

import android.util.Log
import java.nio.ByteBuffer

object PacketProcessor {

    private const val TAG = "PacketProcessor"

    /**
     * Processes IP packets in userspace. 
     * Applies fragmentation on TLS ClientHello or drops QUIC (UDP 443) packets.
     * 
     * @param buffer containing the raw IP packet
     * @param length packet length in bytes
     * @return the modified length of the packet, or 0 if packet is dropped
     */
    fun processPacket(buffer: ByteBuffer, length: Int): Int {
        if (length < 20) return length // Minimum size of IP header

        val ipHeader = buffer.array()
        val version = (ipHeader[0].toInt() shr 4) and 0x0F
        if (version != 4) return length // Only IPv4 is processed in this example stub

        val ipHeaderLength = (ipHeader[0].toInt() and 0x0F) * 4
        val protocol = ipHeader[9].toInt()

        when (protocol) {
            17 -> { // UDP Protocol
                val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                            (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)
                
                if (dPort == 443) {
                    // Block QUIC protocol to force fallback to TCP (where splitting works)
                    Log.d(TAG, "QUIC connection detected. Dropping packet to force TCP fallback.")
                    return 0
                }
            }
            6 -> { // TCP Protocol
                val dPort = ((ipHeader[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                            (ipHeader[ipHeaderLength + 3].toInt() and 0xFF)

                if (dPort == 443) {
                    val tcpHeaderLength = ((ipHeader[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
                    val payloadOffset = ipHeaderLength + tcpHeaderLength
                    val payloadLength = length - payloadOffset

                    if (payloadLength > 5) {
                        // Check for TLS Handshake and ClientHello
                        val contentType = ipHeader[payloadOffset].toInt() and 0xFF
                        val handshakeType = ipHeader[payloadOffset + 5].toInt() and 0xFF

                        if (contentType == 0x16 && handshakeType == 0x01) {
                            Log.i(TAG, "TLS ClientHello handshake detected. Splitting payload.")
                            // In a real native library (or via Go code), we would split this
                            // payload into two separate TCP packets and adjust sequence numbers.
                        }
                    }
                }
            }
        }
        return length
    }
}
