package edu.berkeley.cs.ucie.digital
package logphy

import scala.math._
import chisel3._
import chisel3.util._
import interfaces._

object LinkTrainingState extends ChiselEnum {
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

class MessageRequest extends Bundle {
  /* val msg = UInt(max((new SBReqMsg).getWidth, (new
   * SBExchangeMsg).getWidth).W) */
  val msg = UInt(128.W)
  val reqType = MessageRequestType()
  val timeoutCycles = UInt(64.W)
  val msgTypeHasData = Bool()
}

class MessageRequestStatus extends Bundle {
  val status = MessageRequestStatusType()
  val data = UInt(64.W)
}

/** Param Enums */

object ClockModeParam extends ChiselEnum {
  val strobe = Value(0.U)
  val continuous = Value(1.U)
}

object TransmitPattern extends ChiselEnum {
  val CLOCK_64_LOW_32 = Value(0.U)
}

class SBIO(params: AfeParams) extends Bundle {

  val fifoParams = Input(new FifoParams())

  /** Data to transmit on the sideband.
    *
    * Output from the async FIFO.
    */
  val txData = Decoupled(Bits(params.sbSerializerRatio.W))
  val txValid = Decoupled(Bool())

  /** Data received on the sideband.
    *
    * Input to the async FIFO.
    */
  val rxData = Flipped(Decoupled(Bits(params.sbSerializerRatio.W)))
}

class MainbandIO(
    afeParams: AfeParams,
) extends Bundle {

  val fifoParams = Input(new FifoParams())

  /** Data to transmit on the mainband.
    *
    * Output from the async FIFO.
    *
    * @group data
    */
  val txData = Decoupled(
    Vec(afeParams.mbLanes, Bits(afeParams.mbSerializerRatio.W)),
  )

  /** Data received on the mainband.
    *
    * Input to the async FIFO.
    *
    * @group data
    */
  val rxData = Flipped(
    Decoupled(Vec(afeParams.mbLanes, Bits(afeParams.mbSerializerRatio.W))),
  )
}

class MainbandLaneIO(
    afeParams: AfeParams,
) extends Bundle {

  /** Data to transmit on the mainband.
    */
  val txData = Flipped(
    Decoupled(Bits((afeParams.mbLanes * afeParams.mbSerializerRatio).W)),
  )

  val rxData =
    Decoupled(Bits((afeParams.mbLanes * afeParams.mbSerializerRatio).W))
}

class SidebandLaneIO(
    afeParams: AfeParams,
) extends Bundle {

  /** Data to transmit on the sideband.
    */
  val txData = Flipped(
    Decoupled(Bits((afeParams.sbSerializerRatio).W)),
  )

  val rxData =
    Decoupled(Bits((afeParams.sbSerializerRatio).W))
}
