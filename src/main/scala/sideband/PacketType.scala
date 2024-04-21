package edu.berkeley.cs.ucie.digital
package sideband

import chisel3._

/** Opcode <-> Packet Type (unspecified values are reserved) */
object Opcode extends ChiselEnum {

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

object WplMsgCode extends ChiselEnum {
  val AdvCap                   = Value(0x01.U(8.W)) // MsgInfo 0000h reserved, FFFFh stall
  val FinCap                   = Value(0x02.U(8.W)) // MsgInfo 0000h reserved, FFFFh stall
}

object WplMsgSubCode extends ChiselEnum {
  val Adaptor = Value(0x00.U(8.W)) // MsgInfo 0000h reserved, FFFFh stall
  val CXL = Value(0x01.U(8.W)) // MsgInfo 0000h reserved, FFFFh stall
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
  val LinkTraining_Req      = Value(0x85.U(8.W))
  val LinkTraining_Resp     = Value(0x8A.U(8.W))
  val SbOutofReset          = Value(0x91.U(8.W))
  val SbInitReq             = Value(0x95.U(8.W))
  val SbInitResp            = Value(0x9A.U(8.W))
  val MbInitReq             = Value(0xA5.U(8.W))
  val MbInitResp            = Value(0xAA.U(8.W))
  val MbTrainReq            = Value(0xB5.U(8.W))
  val MbTrainResp           = Value(0xBA.U(8.W))
  val PhyRetrainReq         = Value(0xC5.U(8.W))
  val PhyRetrainResp        = Value(0xCA.U(8.W))
  val RecalTrackReq         = Value(0xD5.U(8.W))
  val RecalTrackResp        = Value(0xDA.U(8.W))
  val TrainErrReq           = Value(0xE5.U(8.W))
  val TrainErrResp          = Value(0xEA.U(8.W))
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
//May's comment: Define different msgsubcode enum objects for different msgcodes?
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

// For MsgCodes 0x85, 0x8A
object LinkTrainingMsgSubCode extends ChiselEnum {
  val StartTxInitiatedD2CPointTest  = Value(0x01.U(8.W)) //Start Tx initiated D2C point test resp
  val lfsrClearErrorReq             = Value(0x02.U(8.W)) //LFSR_clear_error
  // TODO: Define more here p.148
}

// For MsgCodes 0x91, 0x95, 0x9A, 0xA5, 0xAA
object SbMbInitMsgSubCode extends ChiselEnum {
  val SbInitOutofReset          = Value(0x00.U(8.W)) //SBINIT out of Reset
  val SbInitDone                = Value(0x01.U(8.W)) //SBINIT done
  val MbInitCalDone             = Value(0x02.U(8.W)) //MBINIT.CAL Done
  val MbInitRepClkInit          = Value(0x03.U(8.W)) //MBINIT.REPAIRCLK init
  val MbInitRepClkRes           = Value(0x04.U(8.W)) //MBINIT.REPAIRCLK result
  val MbInitRepClkAppRep        = Value(0x05.U(8.W)) //MBINIT.REPAIRCLK apply repair
  val MbInitRepClkCheckRepInit  = Value(0x06.U(8.W)) //MBINIT.REPAIRCLK check repair init
  val MbInitRepClkCheckRes      = Value(0x07.U(8.W)) //MBINIT.REPAIRCLK check results
  val MbInitRepClkDone          = Value(0x08.U(8.W)) //MBINIT.RepairCLK done
  val MbInitRepValInit          = Value(0x09.U(8.W)) //MBINIT.REPAIRVAL init
  val MbInitRepValRes           = Value(0x0A.U(8.W)) //MBINIT.REPAIRVAL result
  val MbInitRepValAppRep        = Value(0x0B.U(8.W)) //MBINIT.REPAIRVAL apply repair
  val MbInitRepValDone          = Value(0x0C.U(8.W)) //{MBINIT.RepairVAL done
  val MbInitRevMbInit           = Value(0x0D.U(8.W)) //{MBINIT.REVERSALMB init
  val MbInitRevMbClrErr         = Value(0x0E.U(8.W)) //MBINIT.REVERSALMB clear error
  val MbInitRevMbRes            = Value(0x0F.U(8.W)) //{MBINIT.REVERSALMB result
  val MbInitRevMbDone           = Value(0x10.U(8.W)) //MBINIT.ReversalMB done
  val MbInitRepMbStart          = Value(0x11.U(8.W)) //{MBINIT.REPAIRMB start
  val MbInitRepMbAppRep         = Value(0x12.U(8.W)) //MBINIT.REPAIRMB Apply repair
  val MbInitRepMbEnd            = Value(0x13.U(8.W)) //MBINIT.REPAIRMB end ???both 0x13 and 0x14 on spec?
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

// Link training-related msg encodings, see p.145-152
// case class MsgWoDataLkTrnEnc(msginfo: MsgInfo.Type, msgcode: MsgCode.Type, msgsubcode: ChiselEnum)

// object MsgWoDataLkTrnEncodings {
//   val startTxInitiatedD2CPointTestResp = MsgWoDataLkTrnEnc(MsgInfo., MsgCode.StartTxInitiatedD2CPointTestResp, MsgSubCode.Active)
//   val lfsrClearErrorReq = MsgWoDataLkTrnEnc(MsgInfo., MsgCode.LFSRClearError, MsgSubCode.PMNAK)

//   // Define more message types here...

//   // Access message types like:
//   // MessageTypes.startTxInitiatedD2CPointTestResp
//   // MessageTypes.lfsrClearErrorReq
// }