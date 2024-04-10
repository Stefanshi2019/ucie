package edu.berkeley.cs.ucie.digital
package logphy

import scala.math._
import chisel3._
import chisel3.util._
import interfaces._

object LinkTrainingState extends ChiselEnum {
  //NOTE: As mbTrain mainly does calibration of analog characteristics, it is not implemented
  //If implemented, it should precede mbInit
  val reset, sbInit, mbInit, linkInit, active, linkError = Value
}

object ResetSubState extends ChiselEnum {
  val INIT, FREQ_SEL_CYC_WAIT, FREQ_SEL_LOCK_WAIT = Value
}

object SBInitSubState extends ChiselEnum {
  val SEND_CLOCK, SEND_LOW, WAIT_CLOCK, SB_OUT_OF_RESET_EXCH,
      SB_OUT_OF_RESET_WAIT, SB_DONE_REQ, SB_DONE_REQ_WAIT, SB_DONE_RESP,
      SB_DONE_RESP_WAIT = Value
  }

/** Sideband Types */

object MsgSource extends ChiselEnum {
    val PATTERN_GENERATOR, SB_MSG_WRAPPER = Value
  }

class SBExchangeMsg extends Bundle {
  val exchangeMsg = UInt(128.W)
}

object MessageRequestStatusType extends ChiselEnum {
  val SUCCESS, ERR = Value
}

class SBReqMsg extends Bundle {
  val msg = UInt(128.W)
}

object MessageRequestType extends ChiselEnum {
  val MSG_EXCH, MSG_REQ, MSG_RESP = Value
}

// Request output from pattern generator
class PatternGenRequestStatus (afeParams: AfeParams) extends Bundle {
  val status = MessageRequestStatusType()
  // From (mb) pattern training:
  val errorPerLane = UInt(afeParams.mbLanes.W)
  val errorAllLane = UInt(log2Ceil(afeParams.mbLanes).W) //4. result of total lane comparison errors
}

class MessageRequest extends Bundle {
  /* val msg = UInt(max((new SBReqMsg).getWidth, (new
   * SBExchangeMsg).getWidth).W) */
  val msg = UInt(128.W)
  val reqType = MessageRequestType()
  val timeoutCycles = UInt(64.W)
  val msgTypeHasData = Bool()
}

// Message output from sb msg request
class MessageRequestStatus extends Bundle {
  val status = MessageRequestStatusType()
  val data = UInt(64.W)
  val msgInfo = UInt(16.W)
}

/** Param Enums */

object ClockModeParam extends ChiselEnum {
  val strobe = Value(0.U)
  val continuous = Value(1.U)
}

object TransmitPattern extends ChiselEnum {
  val NO_PATTERN, CLOCK_64_LOW_32, CLK_REPAIR, VAL_TRAIN,
  MB_REVERSAL, DATA = Value //TODO: more
}

// object MainbandSel extends  ChiselEnum {
//   val DATA, TRACK, VALID, CLK_P, CLK_N = Value
// }