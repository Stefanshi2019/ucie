package edu.berkeley.cs.ucie.digital
package sideband

import chisel3._

/** Opcode <-> Packet Type (unspecified values are reserved) */
object PacketType extends ChiselEnum {

  /** 32b Memory Read */
  val MemoryRead32b = Value("b00000".U(5.W))

  /** 32b Memory Write */
  val MemoryWrite32b = Value("b00001".U(5.W))

  /** 32b Configuration Read */
  val ConfigurationRead32b = Value("b00100".U(5.W))

  /** 32b Configuration Write */
  val ConfigurationWrite32b = Value("b00101".U(5.W))

  /** 64b Memory Read */
  val MemoryRead64b = Value("b01000".U(5.W))

  /** 64b Memory Write */
  val MemoryWrite64b = Value("b01001".U(5.W))

  /** 64b Configuration Read */
  val ConfigurationRead64b = Value("b01100".U(5.W))

  /** 64b Configuration Write */
  val ConfigurationWrite64b = Value("b01101".U(5.W))

  /** Completion without Data */
  val CompletionWithoutData = Value("b10000".U(5.W))

  /** Completion with 32b Data */
  val CompletionWith32bData = Value("b10001".U(5.W))

  /** Message without Data */
  val MessageWithoutData = Value("b10010".U(5.W))

  /** Completion with 64b Data */
  val CompletionWith64bData = Value("b11001".U(5.W))

  /** Message with 64b Data */
  val MessageWith64bData = Value("b11011".U(5.W))
}



  // Link training message too much, will do later, p148

  // Side band message definitions, refer to p146
object MsgCode extends ChiselEnum {
  val Nop                   = Value(0x00.U(8.W))
  val LinkMgmt_RDI_Req      = Value(0x01.U(8.W))
  val LinkMgmt_RDI_Rsp      = Value(0x02.U(8.W))
  val LinkMgmt_Adaptor0_Req = Value(0x03.U(8.W))
  val LinkMgmt_Adaptor0_Rsp = Value(0x04.U(8.W))
  val LinkMgmt_Adaptor1_Req = Value(0x05.U(8.W))
  val LinkMgmt_Adaptor1_Rsp = Value(0x06.U(8.W))
  val ParityFeature_Req     = Value(0x07.U(8.W))
  val ParityFeature_Ack     = Value(0x08.U(8.W))
  val ErrorMsg              = Value(0x09.U(8.W))
}


// // To Do, Chisel doesn't allow two mappings to the same value, need to change to this
// object MyEnum extends ChiselEnum {
//   val Req, Ack, Correctable, Crd, Nak, NonFatal, Active, Fatal, PMNAK, L1, L2, LinkReset, LinkError, Retrain, Disable = Value
// }

// val logicalValueMap: Map[MyEnum.Type, UInt] = Map(
//   MyEnum.Req -> 0.U,
//   MyEnum.Ack -> 0.U,
//   MyEnum.Correctable -> 0.U,
//   MyEnum.Crd -> 0.U,
//   // Add other mappings
//   MyEnum.Nak -> 1.U,
//   MyEnum.NonFatal -> 1.U,
//   MyEnum.Active -> 1.U,
//   // Continue for other values
// )


  // Side band message definitions, refer to p146
object MsgSubCode extends ChiselEnum {


  val Crd       = Value(0x00.U(8.W))  // Nop 0x00
  val Active    = Value(0x01.U(8.W))  // LinkMgmt 0x01 - 0x06

  val PMNAK     = Value(0x02.U(8.W))  // LinkMgmt 0x01 - 0x06
  val L1        = Value(0x04.U(8.W))  // LinkMgmt 0x01 - 0x06
  val L2        = Value(0x08.U(8.W))  // LinkMgmt 0x01 - 0x06
  val LinkReset = Value(0x09.U(8.W))  // LinkMgmt 0x01 - 0x06
  val LinkError = Value(0x0a.U(8.W))  // LinkMgmt 0x01 - 0x06
  val Retrain   = Value(0x0b.U(8.W))  // LinkMgmt 0x01 - 0x06
  val Disable   = Value(0x0c.U(8.W))  // LinkMgmt 0x01 - 0x06


  val Req = Value(0x10.U(8.W))  // ParityFeature 0x07
  val Nak = Value(0x11.U(8.W))  // ParityFeature 0x08 
  val Ack = Value(0x12.U(8.W))  // ParityFeature 0x08

  // // Chisel enum doesn't allow mapping to same value
  // // Change to different value for now, will fix later
  // // Original, commented for now
  // val Req = Value(0x00.U(8.W))  // ParityFeature 0x07

  // val Ack = Value(0x00.U(8.W))  // ParityFeature 0x08
  // val Nak = Value(0x01.U(8.W))  // ParityFeature 0x08

  // val Correctable = Value(0x00.U(8.W))  //ErrorMsg 0x09
  // val NonFatal    = Value(0x01.U(8.W))  //ErrorMsg 0x09
  // val Fatal       = Value(0x02.U(8.W))  //ErrorMsg 0x09
}

object MsgInfo extends ChiselEnum {
  // Credit for Nop only
  // val DontCare = Value(0x0000.U(16.W))
  val RegularResponse = Value(0x0000.U(16.W))
  val ReturnCredit1   = Value(0x0001.U(16.W))
  val ReturnCredit2   = Value(0x0002.U(16.W))
  val ReturnCredit3   = Value(0x0003.U(16.W))
  val ReturnCredit4   = Value(0x0004.U(16.W))
  val StallResponse   = Value(0x0fff.U(16.W))
  // val StallResponse   = Value(0xffff.U(16.W))
  val Stall           = Value(0xffff.U(16.W))

}
