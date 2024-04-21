package edu.berkeley.cs.ucie.digital
package sideband

import chisel3._
import chisel3.util._

// This module converts a message into SidebandMessageHeader() type
// to be sent out upon FSM request 
class SidebandMessageSender extends Module {
    
    val io = IO(
        new SbMsgSubIO()
    )
   
    val msgHeader = new SidebandMessageHeader()
    val srcid = WireInit(SourceID.DieToDieAdapter)

    val rsvd_00 = WireInit(0.U(2.W))
    val rsvd_01 = WireInit(0.U(5.W))
    // val msgCode = WireInit(0.U(8.W))
    val rsvd_02 = WireInit(0.U(9.W))
    // val opcode = WireInit(0.U(5.W)) 
    val dp = WireInit(0.U(1.W))
    val cp = WireInit(0.U(1.W))
    val rsvd_10 = WireInit(0.U(3.W))
    val dstid = WireInit(0.U(3.W))

    // Phase 0
    msgHeader.srcid := srcid 
    msgHeader.rsvd_00 := rsvd_00
    msgHeader.rsvd_01 := rsvd_01 
    msgHeader.msgCode := io.msgCode
    msgHeader.rsvd_02 := rsvd_02 
    msgHeader.opcode := io.opcode
    // Phase 1
    msgHeader.dp := dp 
    msgHeader.cp := cp
    msgHeader.rsvd_10 := rsvd_10 
    msgHeader.dstid := dstid 
    msgHeader.msgInfo := io.msgInfo
    msgHeader.msgSubCode := io.msgInfo

    io.bits.bits := Cat(
        msgHeader.srcid.asUInt, 
        msgHeader.rsvd_00,
        msgHeader.rsvd_01,
        msgHeader.msgCode.asUInt,
        msgHeader.rsvd_02,
        msgHeader.opcode.asUInt,
        msgHeader.dp,
        msgHeader.cp,
        msgHeader.rsvd_10,
        msgHeader.dstid.asUInt,
        msgHeader.msgInfo.asUInt,
        msgHeader.msgSubCode.asUInt
    )

    // bits no back pressure allowed, only valid and bits
    io.bits.valid := io.handshake.valid 
    io.handshake.ready := true.B // always enable for now
}