package edu.berkeley.cs.ucie.digital
package sideband

import chisel3._
import chisel3.util._

// This module turns message to be sent into a 32 bit stream
class SidebandMessageReceiver extends Module {
    val io = IO( 
        Flipped(new SbMsgSubIO())
    )

    val phaseCounter = RegInit(0.U(32.W))
    val msgHeader = new SidebandMessageHeader()

    msgHeader.srcid := io.bits.bits(31+32, 29+32)
    msgHeader.rsvd_00 := io.bits.bits(28+32, 27+32)
    msgHeader.rsvd_01 := io.bits.bits(26+32, 22+32)
    msgHeader.msgCode := io.bits.bits(21+32, 14+32)
    msgHeader.rsvd_02 := io.bits.bits(13+32, 5+32)
    msgHeader.opcode := io.bits.bits(4+32, 0+32)

    msgHeader.dp := io.bits.bits(31)
    msgHeader.cp := io.bits.bits(30)
    msgHeader.rsvd_10 := io.bits.bits(29, 27)
    msgHeader.dstid := io.bits.bits(26, 24)
    msgHeader.msgInfo := io.bits.bits(23, 8)
    msgHeader.msgSubCode := io.bits.bits(7, 0)
    
    io.msgCode := msgHeader.msgCode 
    io.msgInfo := msgHeader.msgInfo
    io.msgSubCode := msgHeader.msgSubCode 
    io.opcode := msgHeader.opcode 

    io.handshake.valid := io.bits.valid 

    
}
