package edu.berkeley.cs.ucie.digital
package sideband

import chisel3._

/** UCIe Spec 6.1 */
class RegisterSidebandHeader extends Bundle {
  // Phase 0
  val srcid = SourceID()
  val rsvd_00 = Bits(2.W)

  /** Tag is a 5-bit field generated by the requester, and it must be unique for
    * all outstanding requests that require a completion. The original requester
    * uses the Tag to associate returning completions with the original request.
    */
  val tag = Bits(5.W)
  val be = Bits(8.W)
  val rsvd_01 = Bits(8.W)
  val ep = Bool()
  val opcode = PacketType()

  // Phase 1
  val dp = Bool()
  val cp = Bool()
  val cr = Bool()
  val rsvd_10 = Bits(2.W)
  val dstid = Bits(3.W)
  val rsvd_11 = Bits(21.W)
  val status = Bits(3.W)

}

// Append 32 or 64 bits of data if required in phase 2 and 3
class SidebandMessageHeader extends Bundle {
  // Phase 0
  val srcid = Bits(3.W)
  val rsvd_00 = Bits(2.W)
  val rsvd_01 = Bits(5.W)
  val msgCode = Bits(8.W)
  val rsvd_02 = Bits(9.W)
  val opcode = Bits(5.W)
  // Phase 1
  val dp = Bool()
  val cp = Bool()
  val rsvd_10 = Bits(3.W)
  val dstid = Bits(3.W)
  val msgInfo = Bits(16.W)
  val msgSubCode = Bits(8.W)
}

